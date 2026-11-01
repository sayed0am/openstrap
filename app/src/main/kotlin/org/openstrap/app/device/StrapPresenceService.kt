package org.openstrap.app.device

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.pm.PackageManager
import org.openstrap.app.OpenStrapApp

/**
 * Android binds this while the associated strap is in range (see [CompanionLink]), starting the process if it
 * was killed. Each "appeared" event reconnects; the connection's own retries handle the strap going out of range.
 */
class StrapPresenceService : CompanionDeviceService() {

    private fun appeared(what: String) {
        val strap = (application as OpenStrapApp).container.strap
        (strap as? StrapManager)?.log("companion: strap appeared ($what)")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) strap.backgroundConnect()
    }

    // Which of these Android calls varies by version (Android 16 can call more than one for the same event);
    // autoConnect is a no-op while a connection is up or under way, so a repeat is harmless.
    @Deprecated("Android 12")
    override fun onDeviceAppeared(address: String) = appeared("address $address")

    @Deprecated("Android 13 to 15")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared("association ${associationInfo.id}")

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) appeared("presence event, association ${event.associationId}")
    }
}
