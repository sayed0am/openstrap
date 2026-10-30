package org.openstrap.app.device

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Android binds this while the associated strap is in range (see [CompanionLink]), starting the process if it
 * was killed. Each "appeared" event reconnects; the connection's own retries handle the strap going out of range.
 */
@RequiresApi(Build.VERSION_CODES.S)
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
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared("association ${associationInfo.id}")

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) appeared(tr(R.string.strap_presence_service_presence_event_association, event.associationId))
    }
}
