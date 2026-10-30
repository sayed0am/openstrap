package org.openstrap.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.openstrap.app.MainActivity
import org.openstrap.app.data.BodySignalsCopy
import org.openstrap.app.ui.dashboard.Metric
import org.openstrap.protocol.services.HealthAlert
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Shows the strap's own health alerts (0x001D `0D`) as notifications, like Zepp's "Health Reminders" channel
 * (07 §4 N6). The strap only raises them for the alerts switched on in its settings.
 */
class HealthAlertNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.common_health_alerts), NotificationManager.IMPORTANCE_HIGH))
    }

    fun show(alert: HealthAlert) {
        val now = System.currentTimeMillis() / 1000
        // The timestamp unit isn't confirmed on hardware (03 §7); fall back to now if it doesn't look like seconds.
        val at = alert.timestamp.takeIf { it in MIN_EPOCH..now + 86_400 } ?: now
        val (title, text) = when (alert.type) {
            HealthAlert.Type.HIGH_HEART_RATE -> tr(R.string.health_alert_notifier_high_heart_rate) to tr(R.string.health_alert_notifier_your_heart_rate_reached, alert.value)
            HealthAlert.Type.LOW_HEART_RATE -> tr(R.string.health_alert_notifier_low_heart_rate) to tr(R.string.health_alert_notifier_your_heart_rate_dropped, alert.value)
            HealthAlert.Type.HIGH_STRESS -> tr(R.string.health_alert_notifier_high_stress) to tr(R.string.health_alert_notifier_your_stress_is_at, alert.value)
            HealthAlert.Type.LOW_SPO2 -> tr(R.string.health_alert_notifier_low_blood_oxygen) to tr(R.string.health_alert_notifier_your_blood_oxygen_was, alert.value)
            HealthAlert.Type.IRREGULAR_RHYTHM ->
                tr(R.string.common_irregular_heart_rhythm) to tr(R.string.health_alert_notifier_the_strap_noticed_an, TIME.format(Instant.ofEpochSecond(at)))
        }
        val metric = when (alert.type) {
            HealthAlert.Type.HIGH_HEART_RATE, HealthAlert.Type.LOW_HEART_RATE, HealthAlert.Type.IRREGULAR_RHYTHM -> Metric.HeartRate
            HealthAlert.Type.HIGH_STRESS -> Metric.Stress
            HealthAlert.Type.LOW_SPO2 -> Metric.Spo2
        }
        val day = Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val open = openRoute(metric.name, day, NOTIFICATION_BASE + alert.type.code)
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
        val open = openRoute(tr(R.string.common_recovery), java.time.LocalDate.now(), BODY_SIGNALS_ID)
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

    private companion object {
        const val CHANNEL = "health"
        const val BODY_SIGNALS_ID = 99
        const val NOTIFICATION_BASE = 100
        const val MIN_EPOCH = 1_704_067_200L // 2024-01-01
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
}
