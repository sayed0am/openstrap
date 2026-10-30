package org.openstrap.app.ui.workout

import org.openstrap.app.data.Route
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.ui.glow.km
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * What Choose and Ready say (workouts spec 2.1, 2.2, 7): the sport, the goal and what the strap will do, never the
 * family. Pure, so it's tested without the screens.
 */
object StartCopy {
    /** The strap's one line on Choose: "Strap ready, 72 bpm", or what's wrong. */
    fun strapLine(connection: ConnectionState, bpm: Int?): String = when (connection) {
        is ConnectionState.Connected -> if (bpm != null) "Strap ready, $bpm bpm" else "Strap ready"
        ConnectionState.Connecting, ConnectionState.Authenticating -> "Connecting to the strap"
        ConnectionState.NotPaired -> "No strap paired. You can start anyway."
        ConnectionState.Disconnected, is ConnectionState.Failed -> "Strap not connected. You can start anyway."
    }

    /** The goal chip for no goal, named for the sport: "Just run", "Just walk", "Just ride", else "Just go". */
    fun noGoal(type: Int): String = when {
        type in Workouts.RUNS -> "Just run"
        type in Workouts.WALKS -> "Just walk"
        Workouts.tileType(type) == 4 -> "Just ride"
        else -> "Just go"
    }

    /** Mind and body's length chip for none. */
    const val OPEN = "Open"

    /** Above the goal chips, or the length chips for mind and body. */
    fun goalTitle(family: Workouts.Family): String = if (family == Workouts.Family.MindBody) "Length" else "Goal"

    /** What a goal does on the strap, said on Ready (spec 7: what the strap or phone will do, in one sentence). */
    fun goalStatus(goal: WorkoutTarget?): String = when (goal) {
        is WorkoutTarget.Time, is WorkoutTarget.Distance -> "The strap buzzes at the halfway mark and when you reach your goal."
        is WorkoutTarget.Kcal -> "The strap buzzes when you reach ${goal.label}."
        is WorkoutTarget.Zones -> "The strap buzzes if you drift out of ${goal.label.lowercase().replaceFirstChar { it.uppercase() }}."
        null -> "The strap records every heart beat. Nothing buzzes until you stop."
    }

    const val STRENGTH_STATUS = "The strap buzzes when rest is over, so the phone can stay on the bench."
    const val GAMES_STATUS = "Phone in your bag? That's fine."

    /** The GPS line on Ready for an outdoor sport: finding a fix with its accuracy, then ready. */
    fun gpsStatus(accuracy: Float?): String = when {
        accuracy == null || accuracy > 1000 -> "Finding GPS"
        accuracy > Route.MAX_ACCURACY_M -> "Finding GPS, ±${accuracy.toInt()} m"
        else -> "GPS ready"
    }

    /** Whether [gpsStatus] says ready. */
    fun gpsReady(accuracy: Float?): Boolean = accuracy != null && accuracy <= Route.MAX_ACCURACY_M

    const val GPS_OFF = "GPS off for this sport. Turn it on behind the settings."

    /** Strength's plan in one line: "5 sets, 90 s rest, or sooner once you're under 110 bpm". */
    fun plan(sets: Int?, rest: Strength.Rest): String = listOfNotNull(
        sets?.let { "$it sets" } ?: "Open sets",
        "${restText(rest.seconds)} rest",
    ).joinToString(", ") + (rest.belowBpm?.let { ", or sooner once you're under $it bpm" } ?: "")

    /** Under two minutes in seconds ("90 s"), then minutes: "2 min", "2 min 30 s". */
    fun restText(seconds: Long): String = when {
        seconds < 120 -> "$seconds s"
        seconds % 60 == 0L -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    /** The sets stepper's value: the count, or Open for no plan. */
    fun setsValue(sets: Int?): String = sets?.toString() ?: "Open"

    /** A recent sport's card on Choose: last time's settings in one line ("5 sets, 90 s rest", "45 min", "Just run"). */
    fun settingsLine(type: Int, s: SportSettings): String {
        if (Workouts.family(type) == Workouts.Family.Strength) {
            val rest = Strength.Rest.parse(s.rest) ?: Strength.Rest.DEFAULT
            return listOfNotNull(s.sets?.let { "$it sets" }, "${restText(rest.seconds)} rest").joinToString(", ")
        }
        return WorkoutTarget.parse(s.goal)?.let(::goalLabel) ?: if (Workouts.family(type) == Workouts.Family.MindBody) OPEN else noGoal(type)
    }

    /** A goal on a chip: "5 km", "30 min", "Zone 2", "300 kcal". */
    fun goalLabel(goal: WorkoutTarget): String = when (goal) {
        is WorkoutTarget.Zones -> goal.label
        is WorkoutTarget.Time -> goal.label
        is WorkoutTarget.Kcal -> goal.label
        is WorkoutTarget.Distance -> goal.label
    }

    /**
     * Your usual's line: what you did last time and when ("Last time 5.2 km, Tuesday"): the distance for a distance
     * sport that has one, the sets for strength, else the minutes.
     */
    fun lastTime(a: WorkoutAnalysis, today: LocalDate, zone: java.time.ZoneId): String {
        val what = when {
            a.distanceMeters != null && a.distanceMeters >= 100 && Workouts.family(a.workout.sportType) == Workouts.Family.Distance ->
                km(a.distanceMeters) + if (a.distanceMeters >= 1000) " km" else ""
            a.sets != null && a.sets > 0 -> if (a.sets == 1) "1 set" else "${a.sets} sets"
            else -> "${(a.activeSeconds / 60).coerceAtLeast(1)} min"
        }
        val day = java.time.Instant.ofEpochSecond(a.workout.start).atZone(zone).toLocalDate()
        return "Last time $what, ${dayWord(day, today)}"
    }

    /** "today", "yesterday", a weekday within the last week ("Tuesday"), else the date ("12 Oct"). */
    fun dayWord(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
        day == today -> "today"
        day == today.minusDays(1) -> "yesterday"
        day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> day.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
}
