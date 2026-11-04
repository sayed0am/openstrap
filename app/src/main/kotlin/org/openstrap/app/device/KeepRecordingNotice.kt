package org.openstrap.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.data.Workouts
import org.openstrap.app.i18n.Strings

/**
 * Shown when Android refuses the workout's foreground service (a start from the background on Android 12 and up, a
 * restore after a widget's broadcast, say): the workout records on, but can be killed. A tap brings the app to the
 * front, where the service may start ([WorkoutSession.inFront]). Cleared once the service runs or the workout ends.
 * On its own channel, which pops up: the workout's own channel is low and stays quiet in the shade, and the notice is
 * there to be seen in time. It stands in for the workout's own notification, which can't show without the service.
 */
object KeepRecordingNotice {
    val TEXT: String get() = Strings.app.get(R.string.system_keep_recording_text)

    /** A channel's importance is fixed once it exists, so the heads-up needed a new id, not the workout's. */
    const val CHANNEL = "workout_keep_recording"

    /** Its own id and request code, apart from the service's (1 to 6), the wear reminder's (17) and health alerts'. */
    internal const val NOTIFICATION_ID = 8
    private const val REQUEST_OPEN = 8

    /** "Keep recording your outdoor run", "Keep recording your HIIT": the sport as the app names it, in a sentence. */
    fun title(sportType: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.system_keep_recording_title, inSentence(Workouts.sportName(sportType)))

    /** Lower case, word by word, except an acronym like HIIT or HYROX. */
    private fun inSentence(name: String): String =
        name.split(" ").joinToString(" ") { w -> if (w.all { it.isUpperCase() || it.isDigit() }) w else w.lowercase() }

    /**
     * High, so it shows as a heads-up, with no sound or vibration: a nudge to open the app, not an alarm. Made here as
     * well as with the service's channels, since the notice shows exactly when the service couldn't start.
     */
    fun createChannel(context: Context) {
        val name = Strings.app.get(R.string.system_channel_keep_recording)
        val channel = NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = Strings.app.get(R.string.system_channel_keep_recording_description)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, sportType: Int) {
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = TEXT
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(title(sportType))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
