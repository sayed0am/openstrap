package org.openstrap.app.device

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.StrapUuids
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.settings.PairedDevice
import org.openstrap.app.data.settings.SettingsStore
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * StrapManager's connect against Robolectric's GATT shadow (audit test 5): a strap that never answers ends in Failed
 * with a reconnect (C1), and connects racing each other leave one client open (C5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StrapConnectTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private lateinit var db: OpenStrapDatabase
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter).setEnabled(true)
        db = inMemoryDb(context)
        settings = SettingsStore(context)
    }

    @After
    fun tearDown() {
        runBlocking { settings.forgetDevice() }
        db.close()
    }

    private fun pair(address: String) = runBlocking {
        settings.savePairedDevice(PairedDevice(address, "00112233445566778899aabbccddeeff", "Helio Strap"))
        settings.setHeldDisconnected(false)
    }

    private fun device(address: String): BluetoothDevice = adapter.getRemoteDevice(address)
    private fun gatts(address: String): List<BluetoothGatt> = shadowOf(device(address)).bluetoothGatts.toList()
    private fun open(address: String) = gatts(address).filterNot { shadowOf(it).isClosed }

    /** The strap's channel characteristics, so connect and notification setup go through. */
    private fun channelService(): BluetoothGattService {
        val service = BluetoothGattService(StrapUuids.MAIN_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for (uuid in listOf(StrapUuids.CHANNEL_CONTROL, StrapUuids.CHANNEL_DATA)) {
            val c = BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            c.addDescriptor(BluetoothGattDescriptor(GattClient.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
            service.addCharacteristic(c)
        }
        return service
    }

    @Test
    fun aGattOpThatNeverAnswersEndsInFailedWithAReconnect() = runTest {
        val address = "AA:BB:CC:00:00:01"
        pair(address)
        val strap = StrapManager(context, settings, db.healthDao(), backgroundScope)
        strap.connect()
        // connectGatt is called, and its callback never comes: the op times out.
        val failed = strap.connection.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("The strap stopped answering.", failed.reason)
        assertFalse(failed.keyRejected)
        assertTrue(strap.log.value.joinToString("\n"), strap.log.value.any { it.endsWith("reconnecting in 5s") })
        // close() ran, so the GATT slot is free.
        assertTrue(gatts(address).isNotEmpty())
        assertTrue(open(address).isEmpty())
    }

    @Test
    fun aSilentStrapOnALiveLinkEndsInFailedWithAReconnect() = runTest {
        val address = "AA:BB:CC:00:00:02"
        pair(address)
        val strap = StrapManager(context, settings, db.healthDao(), backgroundScope)
        strap.connect()
        // connectGatt runs in the same step that moves the state to Connecting.
        strap.connection.first { it == ConnectionState.Connecting }
        val gatt = gatts(address).single()
        shadowOf(gatt).addDiscoverableService(channelService())
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        // The link is up and every write goes out, but the strap never answers the version query: the stuck
        // "Authenticating" of audit C1.
        strap.connection.first { it == ConnectionState.Authenticating }
        val failed = strap.connection.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("The strap stopped answering.", failed.reason)
        assertTrue(strap.log.value.joinToString("\n"), strap.log.value.any { it.endsWith("reconnecting in 5s") })
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aDisconnectDuringSetupIsNotAFailure() = runTest {
        val address = "AA:BB:CC:00:00:03"
        pair(address)
        val strap = StrapManager(context, settings, db.healthDao(), backgroundScope)
        val seen = mutableListOf<ConnectionState>()
        backgroundScope.launch { strap.connection.collect { seen += it } }
        strap.connect()
        strap.connection.first { it == ConnectionState.Connecting }
        assertEquals(1, gatts(address).size)
        strap.disconnect()
        strap.connection.first { it == ConnectionState.Disconnected }
        // Well past every timeout: the cancelled connect neither reports Failed nor schedules a reconnect.
        delay(10 * 60_000L)
        assertTrue("$seen", seen.none { it is ConnectionState.Failed })
        assertEquals(ConnectionState.Disconnected, strap.connection.value)
        assertTrue(strap.log.value.joinToString("\n"), strap.log.value.none { it.contains("reconnecting") })
        assertTrue(open(address).isEmpty())
    }

    @Test
    fun aMissingPermissionFailsForGoodWithoutAReconnect() = runTest {
        val address = "AA:BB:CC:00:00:04"
        pair(address)
        // What connectGatt throws once Nearby devices is turned off for the app.
        shadowOf(device(address)).setGattConnectionInterceptor { throw SecurityException("Bluetooth connect permission required.") }
        val strap = StrapManager(context, settings, db.healthDao(), backgroundScope)
        strap.connect()
        val failed = strap.connection.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertTrue(failed.reason, failed.reason.contains("Nearby devices"))
        delay(10 * 60_000L)
        assertTrue(strap.log.value.joinToString("\n"), strap.log.value.none { it.contains("reconnecting") })
    }

    @Test
    fun aBadAddressFailsForGoodWithoutAReconnect() = runTest {
        pair("not an address")
        val strap = StrapManager(context, settings, db.healthDao(), backgroundScope)
        strap.connect()
        val failed = strap.connection.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertTrue(failed.reason, failed.reason.contains("pair it again"))
        delay(10 * 60_000L)
        assertTrue(strap.log.value.joinToString("\n"), strap.log.value.none { it.contains("reconnecting") })
    }

    @Test
    fun racingConnectsLeaveOneClientOpen() {
        val address = "AA:BB:CC:00:00:05"
        pair(address)
        // Real threads, as the reconnect timer, Bluetooth coming back and the user's tap can all land at once.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val strap = StrapManager(context, settings, db.healthDao(), scope)
            val go = CountDownLatch(1)
            val threads = List(THREADS) {
                thread {
                    go.await()
                    repeat(CALLS) { strap.connect() }
                }
            }
            go.countDown()
            threads.forEach { it.join() }
            runBlocking {
                withTimeout(10_000) { strap.connection.first { it == ConnectionState.Connecting } }
                // Let the last connect reach connectGatt and every cancelled one unwind.
                delay(1_000)
            }
            assertEquals("open clients of ${gatts(address).size}", 1, open(address).size)
            strap.disconnect()
            runBlocking { withTimeout(10_000) { strap.connection.first { it == ConnectionState.Disconnected } } }
            runBlocking { delay(200) }
            assertTrue(open(address).isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val THREADS = 8
        const val CALLS = 25
    }
}
