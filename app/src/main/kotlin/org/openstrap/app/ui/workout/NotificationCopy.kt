package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.toArgb
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.glow.PausedColor
import org.openstrap.app.ui.glow.km
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * What the ongoing workout notification shows (spec 6.3, patterns D4): the black card with the time, bpm in the zone
 * colour, one more figure and the flat zone strip: the time, the bpm, and distance for a GPS workout or the target or
 * the time in this zone otherwise, with the active kcal after the time where it fits. Collapsed it's the sport, the
 * time, the kcal where it fits and the bpm. Each part is its own text element, so nothing here joins them with a
 * separator.
 */
data class WorkoutCard(
    val sport: String,
    /** Active time so far; the card's chronometer counts on from it. */
    val activeSeconds: Long,
    /** Whether the chronometer runs: stopped while paused. */
    val running: Boolean,
    /** The bpm slot: "152 bpm", or "Paused", "Reconnecting", "Waiting" in grey. */
    val bpm: String,
    /** The zone colouring the bpm and [zoneLine]; null draws them grey. */
    val zone: Int?,
    /** Under the bpm: "Zone 3, tempo", "Still recording", or the End question. */
    val zoneLine: String?,
    /** The third figure: "2.40 km", "9 min in this zone", "Goal 30 min". */
    val detail: String?,
    /** Seconds in each zone, for the strip. */
    val zoneSeconds: List<Int>,
    /** "312 kcal", active, after the time; null without a profile. */
    val kcal: String? = null,
    /** Whether [kcal] fits the collapsed line, and the expanded card's time row ([NotificationCopy.kcalFits]). */
    val kcalCollapsed: Boolean = false,
    val kcalExpanded: Boolean = false,
) {
    /** The card as one line, for a Live Update's text: "152 bpm. Zone 3, tempo. 2.40 km". */
    val text: String get() = listOfNotNull(bpm, zoneLine, detail).joinToString(". ")
}

object NotificationCopy {
    /** The question while End waits for a second tap. */
    val END_QUESTION: String get() = tr(R.string.notification_copy_end_the_workout_tap)

    /** Grey for the bpm slot without a zone: the live screen's paused colour. */
    val MUTED = PausedColor

    /**
     * The card for a workout recording now. [bpm] is the live reading while the strap is [connected]. [distanceMeters]
     * counts only for a [gps] workout, marked ≈ when it came from steps (GPS refused after a restart). [confirming]
     * puts the End question where the zone goes.
     */
    fun card(
        sport: String, activeSeconds: Long, paused: Boolean, connected: Boolean, bpm: Int?, maxHr: Int, gps: Boolean,
        distanceMeters: Float?, distanceFromSteps: Boolean, target: WorkoutTarget?, zoneSeconds: List<Int>, confirming: Boolean,
        activeKcal: Float? = null,
    ): WorkoutCard {
        val reading = bpm.takeIf { connected && !paused }
        val zone = WorkoutRecorder.liveZone(reading, maxHr)
        val slot = when {
            paused -> tr(R.string.common_paused)
            !connected -> tr(R.string.common_reconnecting)
            reading == null -> tr(R.string.common_waiting)
            else -> "$reading bpm"
        }
        val zoneLine = when {
            confirming -> END_QUESTION
            paused -> null
            !connected -> tr(R.string.notification_copy_still_recording)
            else -> zone?.let { WorkoutCopy.zoneTitle(it).let { (a, b) -> "$a $b" } }
        }
        val detail = when {
            gps -> distanceMeters?.takeIf { it >= 10 }?.let { (if (distanceFromSteps) "≈ " else "") + km(it) + if (it >= 1000) " km" else "" }
            paused -> null
            target is WorkoutTarget.Zones -> {
                val inTarget = zoneSeconds.withIndex().filter { it.index in target.low..target.high }.sumOf { it.value }
                tr(R.string.notification_copy_min_in, inTarget / 60, target.label)
            }
            target is WorkoutTarget.Time -> tr(R.string.notification_copy_goal, target.label)
            target is WorkoutTarget.Kcal -> tr(R.string.notification_copy_goal, target.label)
            zone != null -> WorkoutCopy.zoneTime(zoneSeconds.getOrElse(zone) { 0 })
            else -> null
        }
        val kcal = WorkoutCopy.kcalText(activeKcal)
        val clock = org.openstrap.app.ui.glow.workoutClock(activeSeconds)
        return WorkoutCard(
            sport, activeSeconds, !paused, slot, zone, zoneLine, detail, zoneSeconds,
            kcal, kcal != null && kcalFits(sport, clock, kcal, slot, reading = zone != null, collapsed = true),
            kcal != null && kcalFits(sport, clock, kcal, slot, reading = zone != null, collapsed = false),
        )
    }

    /**
     * Whether [kcal] fits after the [clock] without cutting anything short, on a 360 dp phone, counted in characters
     * of the line's widths. Collapsed, the sport, time, kcal and bpm share one 14 sp line: about [COLLAPSED_CHARS].
     * Expanded, the time and the bpm [slot] are 30 sp (a word in it 20 sp) beside the kcal at 15 sp: about
     * [EXPANDED_UNITS] of the kcal's characters, the large ones counting two.
     */
    fun kcalFits(sport: String, clock: String, kcal: String, slot: String, reading: Boolean, collapsed: Boolean): Boolean =
        if (collapsed) sport.length + clock.length + kcal.length + slot.length <= COLLAPSED_CHARS
        else 2 * clock.length + kcal.length + (if (reading) 2 * slot.length else (slot.length * 4 + 2) / 3) <= EXPANDED_UNITS

    private const val COLLAPSED_CHARS = 32
    private const val EXPANDED_UNITS = 34

    /** The colour of the bpm and the zone line: the zone's, or [MUTED]. */
    fun accent(zone: Int?): Int = (zone?.let { ZONE_COLORS.getOrNull(it) } ?: MUTED).toArgb()

    /**
     * Each zone's share of a strip [width] pixels wide, in whole pixels that add up to it (largest remainder), so the
     * strip is drawn without gaps. All zeros before any zone time.
     */
    fun stripWidths(zoneSeconds: List<Int>, width: Int): IntArray {
        val total = zoneSeconds.sumOf { it.coerceAtLeast(0).toLong() }
        val out = IntArray(Workouts.ZONES)
        if (total <= 0 || width <= 0) return out
        val exact = (0 until Workouts.ZONES).map { zoneSeconds.getOrElse(it) { 0 }.coerceAtLeast(0) * width.toDouble() / total }
        exact.forEachIndexed { i, v -> out[i] = v.toInt() }
        val left = width - out.sum()
        exact.indices.sortedByDescending { exact[it] - out[it] }.take(left).forEach { out[it]++ }
        return out
    }
}
