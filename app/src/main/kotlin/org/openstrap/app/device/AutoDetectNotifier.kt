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
import org.openstrap.app.data.db.Workout
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.Ranges
import java.time.ZoneId

/**
 * Tells the user about a workout the strap recognised on its own, like Zepp's "Auto Detected Activity" channel
 * (codemap 05 §8). It arrives through history sync, so the notification comes after the workout, not during it.
 */
class AutoDetectNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, Strings.app.get(R.string.system_channel_auto_detected), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun show(w: Workout) {
        // Opens this workout; one request code per workout keeps each notification's extra its own.
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_WORKOUT, w.start)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, w.start.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_unstrap)
            .setContentTitle(title(w))
            .setContentText(Strings.app.get(R.string.system_auto_detected_text))
            .setWhen(w.end * 1000)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Zepp keys it by trackId, so a re-sent workout replaces its own notification.
        manager.notify(w.start.toInt(), notification)
    }

    companion object {
        private const val CHANNEL = "workout_auto_detected"

        /**
         * "Running detected, 10:02 to 10:40", in the phone's clock as every time is (decision 4 in
         * [org.openstrap.app.presentation.Units]).
         */
        fun title(w: Workout, zone: ZoneId = ZoneId.systemDefault(), strings: Strings = Strings.app): String =
            strings.format(R.string.system_auto_detected_title, Workouts.sportName(w.sportType), Ranges.times(w.start, w.end, zone))
    }
}
