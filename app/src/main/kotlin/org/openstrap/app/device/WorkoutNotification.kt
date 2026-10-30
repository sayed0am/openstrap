package org.openstrap.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import org.openstrap.app.MainActivity
import org.openstrap.app.R
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.workout.NotificationCopy
import org.openstrap.app.ui.workout.WorkoutCard
import org.openstrap.app.ui.workout.ZONE_COLORS
import org.openstrap.app.tr

/**
 * The ongoing workout notification as the black card (patterns D4), for phones that don't show it as a Live Update,
 * Samsung among them. Custom views in the system's decorated frame: the header and the action buttons stay the
 * system's, the card is ours. The time is a chronometer, so it ticks without posts and stops where it was when paused.
 * The debug sample in MainActivity builds through here too, so what it shows is what a workout shows.
 */
object WorkoutNotification {
    const val CHANNEL = "workout"

    /** Pixels across the zone strip's bitmap; one pixel tall, the image view stretches it to the card's width. */
    private const val STRIP_PX = 600

    /** The strip's track before any zone time. */
    private const val STRIP_EMPTY = 0x26FFFFFF

    /** The launcher icon's background black, the card's colour; colorized, the whole notification takes it. */
    const val CARD_DARK = 0xFF0B0D10.toInt()

    /** Below this the bpm slot holds a word ("Paused") and not a reading, drawn smaller to fit by the time. */
    private const val WORD_SP = 20f
    private const val READING_SP = 30f

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.common_workout), NotificationManager.IMPORTANCE_LOW))
    }

    /**
     * The actions under the card: Pause and Lap while it runs, Resume while paused, End, or the End question's answers.
     * [intent] makes each one's pending intent from its service action.
     */
    fun actions(paused: Boolean, confirming: Boolean, intent: (String) -> PendingIntent): List<NotificationCompat.Action> = when {
        confirming -> listOf(action(tr(R.string.workout_notification_yes_end), intent(StrapService.ACTION_END)), action(tr(R.string.common_cancel), intent(StrapService.ACTION_END_CANCEL)))
        paused -> listOf(action(tr(R.string.common_resume), intent(StrapService.ACTION_RESUME)), action(tr(R.string.common_end), intent(StrapService.ACTION_END_ASK)))
        else -> listOf(
            action(tr(R.string.common_pause), intent(StrapService.ACTION_PAUSE)), action(tr(R.string.common_lap), intent(StrapService.ACTION_LAP)),
            action(tr(R.string.common_end), intent(StrapService.ACTION_END_ASK)),
        )
    }

    private fun action(title: String, intent: PendingIntent) = NotificationCompat.Action(0, title, intent)

    /** The card for [card] with [actions], opening [open] on a tap. Colorized only counts for a foreground service's. */
    fun build(context: Context, card: WorkoutCard, actions: List<NotificationCompat.Action>, open: PendingIntent): NotificationCompat.Builder {
        val accent = NotificationCopy.accent(card.zone)
        val base = SystemClock.elapsedRealtime() - card.activeSeconds * 1000
        val background = if (card.running) R.drawable.notification_card else R.drawable.notification_card_paused
        val reading = card.zone != null
        val collapsed = RemoteViews(context.packageName, R.layout.notification_workout_collapsed).apply {
            setInt(R.id.card, "setBackgroundResource", background)
            setTextViewText(R.id.sport, card.sport)
            setChronometer(R.id.time, base, null, card.running)
            // Empty rather than gone, so it still pushes the bpm to the end.
            setTextViewText(R.id.kcal, if (card.kcalCollapsed) card.kcal else "")
            setTextViewText(R.id.bpm, card.bpm)
            setTextColor(R.id.bpm, accent)
        }
        val expanded = RemoteViews(context.packageName, R.layout.notification_workout_expanded).apply {
            setInt(R.id.card, "setBackgroundResource", background)
            setChronometer(R.id.time, base, null, card.running)
            setTextViewText(R.id.kcal, if (card.kcalExpanded) card.kcal else "")
            setTextViewText(R.id.bpm, card.bpm)
            setTextColor(R.id.bpm, accent)
            setTextViewTextSize(R.id.bpm, TypedValue.COMPLEX_UNIT_SP, if (reading) READING_SP else WORD_SP)
            setTextViewText(R.id.zone, card.zoneLine.orEmpty())
            setTextColor(R.id.zone, if (card.zoneLine == NotificationCopy.END_QUESTION) 0xFFFFFFFF.toInt() else accent)
            setTextViewText(R.id.detail, card.detail.orEmpty())
            setViewVisibility(R.id.lines, if (card.zoneLine == null && card.detail == null) View.GONE else View.VISIBLE)
            setImageViewBitmap(R.id.strip, strip(card.zoneSeconds))
        }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_openstrap)
            .setContentTitle(card.sport)
            .setContentText(card.text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setColor(CARD_DARK)
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The card has its own clock; a second one in the header would only repeat it.
            .setShowWhen(false)
            .setContentIntent(open)
        actions.forEach(b::addAction)
        return b
    }

    /**
     * Debug: the card with sample figures, to check it on a phone without a workout. [kind] is "running" (an outdoor
     * run with GPS), "paused", "indoor" (no GPS), "end" (the End question) or "clear". Nothing records, nothing is
     * written and the buttons only open the app. [StrapService] shows it in place of its own notification, since
     * Android colours the whole card black only for a foreground service's notification.
     */
    fun sample(context: Context, kind: String): Notification {
        val outdoor = kind != "indoor"
        val paused = kind == "paused"
        val confirming = kind == "end"
        val card = NotificationCopy.card(
            Workouts.sportName(if (outdoor) 1 else 8), activeSeconds = 23 * 60 + 41, paused = paused, connected = true,
            bpm = 152, maxHr = 190, gps = outdoor, distanceMeters = 4210f, distanceFromSteps = false, target = null,
            zoneSeconds = listOf(180, 420, 540, 260, 21), confirming = confirming, activeKcal = 286f,
        )
        val open = PendingIntent.getActivity(
            context, SAMPLE_REQUEST,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_WORKOUT, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        createChannel(context)
        return build(context, card, actions(paused, confirming) { open }, open).build()
    }

    private const val SAMPLE_REQUEST = 91

    /** The flat strip: each zone's colour as long as its share of the time, a dim track before there's any. */
    private fun strip(zoneSeconds: List<Int>): Bitmap {
        val bitmap = Bitmap.createBitmap(STRIP_PX, 1, Bitmap.Config.ARGB_8888)
        val widths = NotificationCopy.stripWidths(zoneSeconds, STRIP_PX)
        if (widths.sum() == 0) {
            bitmap.eraseColor(STRIP_EMPTY)
            return bitmap
        }
        val canvas = Canvas(bitmap)
        val paint = Paint()
        var x = 0
        widths.forEachIndexed { zone, w ->
            if (w == 0) return@forEachIndexed
            paint.color = ZONE_COLORS[zone].toArgb()
            canvas.drawRect(x.toFloat(), 0f, (x + w).toFloat(), 1f, paint)
            x += w
        }
        return bitmap
    }
}
