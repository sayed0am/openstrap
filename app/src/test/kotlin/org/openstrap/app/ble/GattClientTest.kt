package org.openstrap.app.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** GattClient against Robolectric's GATT shadow (audit C1, C8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GattClientTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun gattOf(address: String): BluetoothGatt = shadowOf(adapter.getRemoteDevice(address)).bluetoothGatts.last()

    @Test
    fun aTimeoutIsAPlainException() = runTest {
        val client = GattClient(context, "AA:BB:CC:00:01:01")
        val e = runCatching { client.connect() }.exceptionOrNull()
        assertTrue("got $e", e is GattTimeoutException)
        assertFalse("got $e", e is CancellationException)
    }

    @Test
    fun aCallbackForAnotherOpIsNotItsAnswer() = runTest {
        val address = "AA:BB:CC:00:01:02"
        val client = GattClient(context, address)
        val connecting = async { client.connect() }
        runCurrent()
        val gatt = gattOf(address)
        val callback = shadowOf(gatt).gattCallback
        val stray = BluetoothGattCharacteristic(StrapUuids.BATTERY, BluetoothGattCharacteristic.PROPERTY_READ, 0)
        // A late write or read callback, or an MTU exchange the strap started, arrives while connect waits.
        callback.onCharacteristicWrite(gatt, stray, BluetoothGatt.GATT_SUCCESS)
        callback.onCharacteristicRead(gatt, stray, byteArrayOf(1), BluetoothGatt.GATT_SUCCESS)
        callback.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertTrue(connecting.isActive)

        shadowOf(gatt).addDiscoverableService(BluetoothGattService(StrapUuids.MAIN_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY))
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        connecting.await()
        assertTrue(client.connected.value)
    }

    @Test
    fun anOpAfterCloseIsAGattException() = runTest {
        val address = "AA:BB:CC:00:01:03"
        val client = GattClient(context, address)
        val connecting = async { client.connect() }
        runCurrent()
        val gatt = gattOf(address)
        shadowOf(gatt).addDiscoverableService(BluetoothGattService(StrapUuids.MAIN_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY))
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        connecting.await()

        client.close()
        val e = runCatching { client.requestMtu(247) }.exceptionOrNull()
        assertTrue("got $e", e is GattException)
    }

    @Test
    fun aDroppedNotificationIsLogged() = runTest {
        val address = "AA:BB:CC:00:01:04"
        val logged = mutableListOf<String>()
        val client = GattClient(context, address) { logged += it }
        val connecting = async { client.connect() }
        runCurrent()
        val gatt = gattOf(address)
        val callback = shadowOf(gatt).gattCallback
        shadowOf(gatt).addDiscoverableService(BluetoothGattService(StrapUuids.MAIN_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY))
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        connecting.await()

        // A collector stuck on its first chunk: the buffer behind it fills and later chunks are dropped.
        val stuck = launch { client.notifications.collect { awaitCancellation() } }
        runCurrent()
        val c = BluetoothGattCharacteristic(StrapUuids.CHANNEL_DATA, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        repeat(BUFFER + 2) { callback.onCharacteristicChanged(gatt, c, byteArrayOf(it.toByte())) }
        assertEquals(logged.toString(), 1, logged.count { it.contains("dropped 1 so far") })
        stuck.cancel()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.connected(address: String, service: BluetoothGattService): Pair<GattClient, BluetoothGatt> {
        val client = GattClient(context, address)
        val connecting = async { client.connect() }
        runCurrent()
        val gatt = gattOf(address)
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        connecting.await()
        return client to gatt
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13ANotificationComesThroughTheOldCallback() = runTest {
        val (client, gatt) = connected("AA:BB:CC:00:01:05", BluetoothGattService(StrapUuids.MAIN_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY))
        val first = async { client.notifications.first() }
        runCurrent()
        val c = BluetoothGattCharacteristic(StrapUuids.CHANNEL_DATA, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        @Suppress("DEPRECATION")
        c.value = byteArrayOf(7, 8)
        @Suppress("DEPRECATION")
        shadowOf(gatt).gattCallback.onCharacteristicChanged(gatt, c)
        // The next notification overwrites the characteristic's value, so the chunk must be a copy.
        @Suppress("DEPRECATION")
        c.value = byteArrayOf(9)
        val n = first.await()
        assertEquals(StrapUuids.CHANNEL_DATA, n.characteristic)
        assertEquals(listOf<Byte>(7, 8), n.value.toList())
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13AReadAnswersThroughTheOldCallback() = runTest {
        val service = BluetoothGattService(StrapUuids.MAIN_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val c = BluetoothGattCharacteristic(StrapUuids.BATTERY, BluetoothGattCharacteristic.PROPERTY_READ, 0)
        service.addCharacteristic(c)
        val (client, gatt) = connected("AA:BB:CC:00:01:06", service)
        val reading = async { client.read(StrapUuids.MAIN_SERVICE, StrapUuids.BATTERY) }
        runCurrent()
        @Suppress("DEPRECATION")
        c.value = byteArrayOf(42)
        @Suppress("DEPRECATION")
        shadowOf(gatt).gattCallback.onCharacteristicRead(gatt, c, BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf<Byte>(42), reading.await().toList())
    }

    @Test
    fun aBadAddressIsUnusable() = runTest {
        val e = runCatching { GattClient(context, "not an address").connect() }.exceptionOrNull()
        assertTrue("got $e", e is GattUnusableException)
    }

    private companion object {
        /** GattClient's notification buffer, plus the one chunk the stuck collector holds. */
        const val BUFFER = 512 + 1
    }
}
