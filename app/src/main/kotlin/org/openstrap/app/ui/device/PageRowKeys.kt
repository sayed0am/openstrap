package org.openstrap.app.ui.device

/**
 * The You and Strap page rows, as the page host names them (`Page.row`) to mark the one whose page is open beside the
 * list on a wide screen. Keys, never shown: the rows' titles come from res/values and follow the app's language, so a
 * row is matched by these instead. They are the English titles the host already uses.
 */
internal object PageRowKeys {
    const val PROFILE = "Profile"
    const val HEART_ZONES = "Heart rate zones"
    const val GOALS_AND_BESTS = "Goals and bests"
    const val HEALTH_CONNECT = "Health Connect"
    const val BACKUP = "Backup"
    const val DELETED_SLEEP = "Deleted sleep"
    const val UPDATES = "Updates"
    const val DIAGNOSTICS = "Diagnostics"
    const val ALARMS = "Alarms"
    const val PHONE_ALERTS = "Phone alerts"
    const val HEALTH_ALERTS = "Health alerts"
    const val FIRMWARE = "Firmware"
    const val ZEPP_ACCOUNT = "Zepp account"
}
