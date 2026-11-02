package org.openstrap.app.device

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The one place that says which permissions the strap needs, so every check and request asks for the same ones.
 */
object BlePermissions {
    /** What scanning for and talking to the strap needs. Scanning never derives location (neverForLocation). */
    val bluetooth: List<String> = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    /**
     * What the app asks for before it pairs, connects or starts a workout: [bluetooth], plus notifications for the
     * foreground service's notification on Android 13 and later, where they need asking. Only [bluetooth] has to be
     * granted for the action to go ahead.
     */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (bluetooth + Manifest.permission.POST_NOTIFICATIONS).toTypedArray()
        } else {
            bluetooth.toTypedArray()
        }

    /** Whether the strap may be scanned for and connected to now. */
    fun granted(context: Context): Boolean =
        bluetooth.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Whether a [RequestMultiplePermissions][androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions]
     * result allows the strap.
     */
    fun granted(result: Map<String, Boolean>): Boolean = bluetooth.all { result[it] == true }

    /**
     * Whether [permissions], just turned down, were turned down for good: Android shows no rationale for any of them,
     * so a new request returns at once with no dialog, and only OpenStrap's settings page ([settingsIntent]) can turn
     * them on. Ask it only in a request's answer, since before the first request no rationale is due either.
     */
    fun turnedDownForGood(activity: Activity, permissions: Collection<String>): Boolean =
        permissions.none { activity.shouldShowRequestPermissionRationale(it) }

    /** OpenStrap's page in system settings, where a permission turned down for good can be turned back on. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
