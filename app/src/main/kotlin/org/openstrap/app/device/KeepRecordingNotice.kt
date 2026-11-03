package org.openstrap.app.device

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.data.Workouts

/**
 * Shown when Android refuses the workout's foreground service (a start from the background on Android 12 and up, a
 * restore after a widget's broadcast, say): the workout records on, but can be killed. A tap brings the app to the
 * front, where the service may start ([WorkoutSession.inFront]). Cleared once the service runs or the workout ends.
 * On the workout channel: it stands in for the workout's own notification, which can't show without the service.
 */
object KeepRecordingNotice {
    const val TEXT = "Android may stop it while OpenStrap is closed. Tap to keep it going."

    /** Its own id and request code, apart from the service's (1 to 6), the wear reminder's (17) and health alerts'. */
    internal const val NOTIFICATION_ID = 8
    private const val REQUEST_OPEN = 8

    /** "Keep recording your outdoor run", "Keep recording your HIIT": the sport as the app names it, in a sentence. */
    fun title(sportType: Int): String = "Keep recording your ${inSentence(Workouts.sportName(sportType))}"

    /** Lower case, word by word, except an acronym like HIIT or HYROX. */
    private fun inSentence(name: String): String =
        name.split(" ").joinToString(" ") { w -> if (w.all { it.isUpperCase() || it.isDigit() }) w else w.lowercase() }

    fun show(context: Context, sportType: Int) {
        WorkoutNotification.createChannel(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WorkoutNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(title(sportType))
            .setContentText(TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(TEXT))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
