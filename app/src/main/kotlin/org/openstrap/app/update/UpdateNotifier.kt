package org.openstrap.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.ui.device.UpdateCopy

/** Says a new OpenStrap is out, or that it was just installed. Either opens the Updates page. */
class UpdateNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.update_notifier_app_updates), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun available(release: Release) = show(UpdateCopy.availableTitle(release), ReleaseNotes.lead(release.notes) ?: UpdateCopy.AVAILABLE_TEXT)

    fun installed(version: String) = show(UpdateCopy.installedTitle(version), UpdateCopy.INSTALLED_TEXT)

    private fun show(title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // One at a time: a newer release replaces the last one's.
        manager.notify(ID, notification)
    }

    companion object {
        private const val CHANNEL = "app_updates"
        private const val ID = 7301
    }
}
