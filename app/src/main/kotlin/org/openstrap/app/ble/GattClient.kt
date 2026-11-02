package org.openstrap.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

open class GattException(message: String) : Exception(message)

/**
 * A GATT operation got no callback in time. A plain exception, not `withTimeout`'s cancellation, so a caller that
 * rethrows cancellations still sees the failure and can close the client and reconnect (audit C1).
 */
class GattTimeoutException(message: String) : GattException(message)

/**
 * Retrying can't help: the address is malformed or the app lacks the Bluetooth permission. The connect should end
 * for good instead of looping on reconnects (audit E4).
 */
class GattUnusableException(message: String, cause: Throwable) : Exception(message, cause)

/** A characteristic notification. */
class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * Minimal suspend-friendly wrapper around [BluetoothGatt]. Android only allows one outstanding GATT
 * operation at a time, so every operation is serialized through [opLock] and completes via the callback.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val address: String,
    private val log: (String) -> Unit = { Log.w("OpenStrap", it) },
) {

    // Written by ops and close(), read by the binder thread's callbacks.
    @Volatile private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pending: Pending? = null

    /** The op waiting for its callback. A callback of another kind, or for another characteristic, isn't its answer. */
    private class Pending(val kind: Op, val uuid: UUID?, val result: CompletableDeferred<Any?> = CompletableDeferred())

    private enum class Op { CONNECT, DISCOVER, MTU, WRITE, READ, DESCRIPTOR }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _notifications = MutableSharedFlow<Notification>(
        extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val notifications: SharedFlow<Notification> = _notifications
    private val droppedCount = AtomicInteger()

    @Volatile var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                complete(Op.CONNECT, null, Unit)
            } else {
                _connected.value = false
                // The link is gone, so whatever was pending can't be answered.
                failAny("Disconnected (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) {
                complete(Op.DISCOVER, null, Unit)
            } else {
                fail(Op.DISCOVER, null, "Service discovery failed ($status)")
            }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            // A refused MTU request isn't fatal; we keep the default. An exchange we didn't ask for must not
            // complete whatever else is pending.
            complete(Op.MTU, null, Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) {
                complete(Op.WRITE, c.uuid, Unit)
            } else {
                fail(Op.WRITE, c.uuid, "Write to ${c.uuid} failed ($status)")
            }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) {
                complete(Op.READ, c.uuid, value)
            } else {
                fail(Op.READ, c.uuid, "Read of ${c.uuid} failed ($status)")
            }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.characteristic?.uuid
            if (status == BluetoothGatt.GATT_SUCCESS) {
                complete(Op.DESCRIPTOR, uuid, Unit)
            } else {
                fail(Op.DESCRIPTOR, uuid, "Descriptor write failed ($status)")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (!_notifications.tryEmit(Notification(c.uuid, value))) dropped(c.uuid)
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // The characteristic's value is overwritten by the next notification, so it's copied before it leaves.
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value?.copyOf() ?: ByteArray(0))
        }
    }

    /** tryEmit never suspends, so a full buffer drops the chunk. Logged sparingly: a stuck collector drops many. */
    private fun dropped(uuid: UUID) {
        val n = droppedCount.incrementAndGet()
        if (n == 1 || n % DROP_LOG_EVERY == 0) log("gatt: notification buffer full, dropped $n so far (last from $uuid)")
    }

    private fun Throwable.isDeadBinder(): Boolean =
        generateSequence(this) { it.cause }.any { it is android.os.DeadObjectException }

    /** Called when Bluetooth turns off: no callback reports the link's end then either. */
    fun lost() {
        _connected.value = false
        failAny("Bluetooth turned off")
    }

    private fun matching(kind: Op, uuid: UUID?): Pending? =
        pending?.takeIf { it.kind == kind && (uuid == null || it.uuid == null || it.uuid == uuid) }
    private fun complete(kind: Op, uuid: UUID?, value: Any?) { matching(kind, uuid)?.result?.complete(value) }
    private fun fail(kind: Op, uuid: UUID?, message: String) { matching(kind, uuid)?.result?.completeExceptionally(GattException(message)) }
    private fun failAny(message: String) { pending?.result?.completeExceptionally(GattException(message)) }

    /** The open handle, or a [GattException] once [close] has run, instead of an NPE from a field nulled meanwhile. */
    private fun requireGatt(): BluetoothGatt = gatt ?: throw GattException("Closed")

    private suspend fun <T> op(kind: Op, uuid: UUID? = null, timeoutMs: Long = 10_000, start: () -> Boolean): T = opLock.withLock {
        val p = Pending(kind, uuid)
        pending = p
        try {
            val started = try {
                start()
            } catch (e: SecurityException) {
                throw GattUnusableException("No Bluetooth permission", e)
            } catch (e: RuntimeException) {
                // The phone's Bluetooth service died (a stack restart): this handle is dead and no disconnect callback
                // will ever come, so report the link lost here and let the owner reconnect with a fresh one.
                if (!e.isDeadBinder()) throw e
                _connected.value = false
                throw GattException("Bluetooth restarted on the phone")
            }
            if (!started) throw GattException("GATT operation could not be started")
            val result = withTimeoutOrNull(timeoutMs) { p.result.await() }
                ?: throw GattTimeoutException("No answer to ${kind.name.lowercase()}${uuid?.let { " of $it" }.orEmpty()} in $timeoutMs ms")
            @Suppress("UNCHECKED_CAST")
            result as T
        } finally {
            pending = null
        }
    }

    /** [refreshCache] drops Android's cached service table first; needed after a firmware update. */
    suspend fun connect(refreshCache: Boolean = false) {
        val device: BluetoothDevice = try {
            context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw GattUnusableException("Not a Bluetooth address: $address", e)
        }
        op<Unit>(Op.CONNECT, timeoutMs = 30_000) {
            val g = if (Build.VERSION.SDK_INT >= 37) {
                // Android 17's settings form. Automatic MTU stays off, as the older call leaves it, and callbacks run
                // on the binder thread like before.
                val settings = android.bluetooth.BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false)
                    .setAutomaticMtuEnabled(false)
                    .build()
                device.connectGatt(settings, { it.run() }, callback)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            gatt = g
            g != null
        }
        requireGatt().requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (refreshCache) {
            // Hidden API, but the only way to force re-discovery; harmless if it fails.
            val g = requireGatt()
            runCatching { BluetoothGatt::class.java.getMethod("refresh").invoke(g) }
            kotlinx.coroutines.delay(500)
        }
        op<Unit>(Op.DISCOVER) { requireGatt().discoverServices() }
    }

    suspend fun requestMtu(size: Int): Int {
        op<Unit>(Op.MTU) { requireGatt().requestMtu(size) }
        return mtu
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    private fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(uuid) ?: throw GattException("Characteristic $uuid not found")

    suspend fun enableNotifications(service: UUID, uuid: UUID) {
        val c = characteristic(service, uuid)
        requireGatt().setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: throw GattException("No CCCD on $uuid")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Unit>(Op.DESCRIPTOR, uuid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireGatt().writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                requireGatt().writeDescriptor(cccd)
            }
        }
    }

    suspend fun write(service: UUID, uuid: UUID, value: ByteArray, withResponse: Boolean = false) {
        val c = characteristic(service, uuid)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op<Unit>(Op.WRITE, uuid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireGatt().writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                requireGatt().writeCharacteristic(c)
            }
        }
    }

    suspend fun read(service: UUID, uuid: UUID): ByteArray {
        val c = characteristic(service, uuid)
        return op(Op.READ, uuid) { requireGatt().readCharacteristic(c) }
    }

    fun close() {
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        _connected.value = false
        failAny("Closed")
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DROP_LOG_EVERY = 100
    }
}
