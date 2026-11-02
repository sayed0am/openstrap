package org.openstrap.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/** Names the official app accepts for the Helio Strap (codename "Stuttgart"). */
val HELIO_STRAP_NAMES = listOf("Amazfit Helio Strap", "Stuttgart")

class BleScanner(private val context: Context) {
    /** Emits Helio Straps as they're seen. Requires BLUETOOTH_SCAN (see BlePermissions). */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (HELIO_STRAP_NAMES.any { name.startsWith(it) }) {
                    trySend(FoundDevice(result.device.address, name, result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
