package org.openstrap.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.data.BodySignalsCopy
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.protocol.services.HealthAlert
import java.time.Instant
import java.time.ZoneId

/**
 * Shows the strap's own health alerts (0x001D `0D`) as notifications, like Zepp's "Health Reminders" channel
 * (07 §4 N6). The strap only raises them for the alerts switched on in its settings.
 */
class HealthAlertNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, Strings.app.get(R.string.system_channel_health), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun show(alert: HealthAlert) {
        val now = System.currentTimeMillis() / 1000
        // The timestamp unit isn't confirmed on hardware (03 §7); fall back to now if it doesn't look like seconds.
        val at = alert.timestamp.takeIf { it in MIN_EPOCH..now + 86_400 } ?: now
        val (title, text) = words(alert, at)
        val day = Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val open = openRoute(route(alert.type), day, NOTIFICATION_BASE + alert.type.code)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(at * 1000)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // One notification per kind, so a repeat replaces the last one instead of stacking up.
        manager.notify(NOTIFICATION_BASE + alert.type.code, notification)
    }

    /**
     * OpenStrap's own body-signal alert, in the wellness copy of [BodySignalsCopy]: it never names a condition or a
     * threshold. One notification, replaced by the next.
     */
    fun showBodySignals(signals: List<String>, nights: Int) {
        val text = BodySignalsCopy.pushText(signals, nights)
        // Body signals lead the Recovery screen, so that's where the notification opens.
        val open = openRoute(RECOVERY_ROUTE, java.time.LocalDate.now(), BODY_SIGNALS_ID)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(BodySignalsCopy.TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(BODY_SIGNALS_ID, notification)
    }

    /**
     * Opens the app on [route]'s screen for [day] (`open_metric`, see [MainActivity.EXTRA_OPEN_METRIC]). Each kind of
     * notification has its own [requestCode], so their extras don't overwrite each other.
     */
    private fun openRoute(route: String, day: java.time.LocalDate, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_METRIC, route)
            .putExtra(MainActivity.EXTRA_METRIC_DAY, day.toString())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal companion object {
        /** The alert's title and text, in the app's language. [at] is when it was raised, epoch seconds. */
        fun words(
            alert: HealthAlert, at: Long, zone: ZoneId = ZoneId.systemDefault(), strings: Strings = Strings.app,
        ): Pair<String, String> {
            val (title, text) = when (alert.type) {
                HealthAlert.Type.HIGH_HEART_RATE ->
                    R.string.system_alert_high_heart_rate_title to R.string.system_alert_high_heart_rate_text
                HealthAlert.Type.LOW_HEART_RATE -> R.string.system_alert_low_heart_rate_title to R.string.system_alert_low_heart_rate_text
                HealthAlert.Type.HIGH_STRESS -> R.string.system_alert_high_stress_title to R.string.system_alert_high_stress_text
                HealthAlert.Type.LOW_SPO2 -> R.string.system_alert_low_spo2_title to R.string.system_alert_low_spo2_text
                HealthAlert.Type.IRREGULAR_RHYTHM ->
                    R.string.system_alert_irregular_rhythm_title to R.string.system_alert_irregular_rhythm_text
            }
            val value: Any = if (alert.type == HealthAlert.Type.IRREGULAR_RHYTHM) TimeOfDay.format(at, zone) else alert.value
            return strings.get(title) to strings.format(text, value)
        }

        /**
         * The metric screen an alert opens, by its `Metric` name in ui.dashboard, written out so device code doesn't
         * depend on the screens. A test checks each one still names a metric.
         */
        fun route(type: HealthAlert.Type): String = when (type) {
            HealthAlert.Type.HIGH_HEART_RATE, HealthAlert.Type.LOW_HEART_RATE, HealthAlert.Type.IRREGULAR_RHYTHM -> "HeartRate"
            HealthAlert.Type.HIGH_STRESS -> "Stress"
            HealthAlert.Type.LOW_SPO2 -> "Spo2"
        }

        private const val RECOVERY_ROUTE = "Recovery"
        private const val CHANNEL = "health"
        private const val BODY_SIGNALS_ID = 99
        private const val NOTIFICATION_BASE = 100
        private const val MIN_EPOCH = 1_704_067_200L // 2024-01-01
    }
}
