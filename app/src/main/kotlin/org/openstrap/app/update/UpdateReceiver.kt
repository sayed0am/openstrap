package org.openstrap.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openstrap.app.OpenStrapApp

/**
 * Android's package installer reporting on an install ([AppUpdater.install]), and Android saying the app was replaced.
 * Being woken for that starts the process again, so the strap reconnects without opening the app; when OpenStrap
 * installed this version itself, a notification says so.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as OpenStrapApp).container
        when (intent.action) {
            ACTION_INSTALL_STATUS -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                container.updater.installResult(
                    context,
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    confirm,
                )
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val tag = container.settings.updateInstalling() ?: return@launch
                        container.settings.setUpdateInstalling(null)
                        val version = Releases.version(tag)
                        if (version != null && version == Releases.version(container.updater.installed)) {
                            UpdateNotifier(context).installed(version.joinToString("."))
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "org.openstrap.app.UPDATE_INSTALL_STATUS"
    }
}
