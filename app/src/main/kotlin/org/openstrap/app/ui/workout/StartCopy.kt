package org.openstrap.app.ui.workout

import org.openstrap.app.data.Route
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
import kotlin.math.roundToInt

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

    const val STRENGTH_STATUS = "The strap buzzes when rest is over, so the phone can stay on the bench."
    const val GAMES_STATUS = "Phone in your bag? That's fine."

    /** The GPS line on Ready for an outdoor sport: finding a fix with its accuracy, then ready with it ("GPS ready, 6 m"). */
    fun gpsStatus(accuracy: Float?): String = when {
        accuracy == null || accuracy > 1000 -> "Finding GPS"
        accuracy > Route.MAX_ACCURACY_M -> "Finding GPS, ±${accuracy.toInt()} m"
        else -> "GPS ready, ${accuracy.roundToInt().coerceAtLeast(1)} m"
    }

    /** Whether [gpsStatus] says ready. */
    fun gpsReady(accuracy: Float?): Boolean = accuracy != null && accuracy <= Route.MAX_ACCURACY_M

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

    /** HIIT's presets on Ready, null for Free: their names and what each is. */
    val PRESETS: List<Triple<org.openstrap.app.data.Structure?, String, String>> = listOf(
        Triple(org.openstrap.app.data.Structure.TABATA, "Tabata", "20 s on, 10 off, 8 rounds"),
        Triple(org.openstrap.app.data.Structure.FORTY_TWENTY, "40 / 20", "10 rounds"),
        Triple(org.openstrap.app.data.Structure.EVERY_MINUTE, "Every minute", "12 rounds, rest is what's left"),
        Triple(null, "Free", "No timer, heart rate only"),
    )

    const val INTERVALS_STATUS = "The strap buzzes at every switch, so the phone can stay on the floor."
    const val WARM_UP_NOTE = "No timer until you start the rounds."

    /** A structure's name: a preset's ("Tabata"), else what it is ("6 × 400 m", "8 × 2 min", "30 / 15, 6 rounds"). */
    fun structureName(s: org.openstrap.app.data.Structure): String {
        PRESETS.firstOrNull { it.first?.code == s.warm(false).code }?.let { return it.second }
        return when (s) {
            is org.openstrap.app.data.Structure.Timed -> "${s.work} / ${s.rest}, ${s.rounds} rounds"
            is org.openstrap.app.data.Structure.EveryMinute -> "Every ${restText(s.period)}, ${s.rounds} rounds"
            is org.openstrap.app.data.Structure.Reps -> "${s.rounds} × " + (s.meters?.let { if (it >= 1000 && it % 1000 == 0) "${it / 1000} km" else "$it m" } ?: restText(s.seconds ?: 0))
            is org.openstrap.app.data.Structure.Periods -> periodsName(s)
        }
    }

    /** "Halves", "Quarters" or "3 periods". */
    fun periodsName(s: org.openstrap.app.data.Structure.Periods): String = when (s.rounds) {
        2 -> "Halves"
        4 -> "Quarters"
        else -> "${s.rounds} periods"
    }

    /** A period by its place: "1st half", "3rd quarter", "Period 2". */
    fun periodName(index: Int, of: Int): String {
        val n = index + 1
        val nth = when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
        return when (of) {
            2 -> "$nth half"
            4 -> "$nth quarter"
            else -> "Period $n"
        }
    }

    /** What the break between periods is called. */
    fun breakName(of: Int): String = if (of == 2) "Half time" else "Break"

    /** Mind and body's line on Ready (workouts spec 2.2). */
    const val MIND_STATUS = "Quiet strap and a dim screen. One soft buzz at the end."

    /** A game's choice on Ready, no label: free play or its periods. */
    const val FREE_PLAY = "Free play"

    /** All of a timed structure's time, rounds of work and the rests between, for Ready: "4:00". */
    fun totalTime(s: org.openstrap.app.data.Structure): Long? = when (s) {
        is org.openstrap.app.data.Structure.Timed -> s.rounds * s.work + (s.rounds - 1) * s.rest
        is org.openstrap.app.data.Structure.EveryMinute -> s.rounds * s.period
        is org.openstrap.app.data.Structure.Reps -> null
        is org.openstrap.app.data.Structure.Periods -> s.rounds * s.length + (s.rounds - 1) * s.rest
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

    /* -------------------------------------------- Ready's where --------------------------------------------- */

    /** Start on Ready, naming the sport: "Start run", and "Start treadmill run" on the treadmill. */
    fun startLabel(type: Int): String = if (type == TREADMILL) "Start treadmill run" else WorkoutCopy.startLabel(type)

    /** Where row's title for the side you're on: "Outside", "On a treadmill", "Indoors", "Open water", "In a pool". */
    fun place(sides: Workouts.Sides, indoor: Boolean): String = when {
        !indoor -> if (sides.outdoor == OPEN_WATER) "Open water" else "Outside"
        sides.indoor == TREADMILL -> "On a treadmill"
        sides.indoor == POOL -> "In a pool"
        else -> "Indoors"
    }

    /** The where row's pill, naming the other side: "Treadmill", "Outside", "Indoors", "Pool", "Open water". */
    fun otherPlace(sides: Workouts.Sides, indoor: Boolean): String = when {
        indoor -> if (sides.outdoor == OPEN_WATER) "Open water" else "Outside"
        sides.indoor == TREADMILL -> "Treadmill"
        sides.indoor == POOL -> "Pool"
        else -> "Indoors"
    }

    /** Under an indoor side's title: what you get without GPS. */
    fun indoorNote(type: Int): String = when {
        type in Workouts.RUNS -> "Pace from your steps"
        type in Workouts.ON_FOOT -> "Distance from your steps"
        else -> "Time and your heart, no distance"
    }

    private const val TREADMILL = 2
    private const val POOL = 6
    private const val OPEN_WATER = 7

    /* -------------------------------------------- Ready's goal ---------------------------------------------- */

    /** The goal kind chips' names after the sport's [noGoal]. */
    const val DISTANCE = "Distance"
    const val TIME = "Time"
    const val ZONE = "Zone"
    const val INTERVALS = "Intervals"

    const val TAP_TO_TYPE = "Tap to type"

    /** No goal's note: what's recorded, the distance only where there is one. */
    fun noGoalNote(hasDistance: Boolean): String = if (hasDistance) "No goal. Time, distance and your heart." else "No goal. Time and your heart."

    /** A distance goal's value in km, as typed: "5", "7.5", "12.45", and a half marathon "21.1". */
    fun distanceValue(meters: Int): String =
        if (meters % 1000 == 0) "${meters / 1000}" else String.format(Locale.ROOT, "%.2f", meters / 1000.0).trimEnd('0').trimEnd('.')

    /** What a distance goal does: "The strap buzzes at halfway and at 7.5 km." */
    fun distanceNote(meters: Int): String = "The strap buzzes at halfway and at ${distanceValue(meters)} km."

    /** Typed km ("12.4", "12,4") as metres, 0.1 to [MAX_KM] km; null for anything else. */
    fun parseDistance(text: String): Int? {
        val km = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return (km * 1000).roundToInt().takeIf { it in 100..MAX_KM * 1000 }
    }

    /** Minus or plus ([up]) on a distance: to the next half km, from half a km to [MAX_KM]. */
    fun stepDistance(meters: Int, up: Boolean): Int {
        val step = 500
        val next = if (up) (meters / step + 1) * step else (meters - 1) / step * step
        return next.coerceIn(step, MAX_KM * 1000)
    }

    const val MAX_KM = 300

    /** The distance presets under the value: 3, 5 and 10 km and a half marathon, with their labels. */
    val DISTANCE_PRESETS: List<Pair<Int, String>> = listOf(3000 to "3", 5000 to "5", 10_000 to "10", HALF_MARATHON to "Half")

    const val HALF_MARATHON = 21_097

    /** A time goal's value as numbers and units: "1" "h" "15" "min", "1" "h", or "45" "min" under an hour. */
    fun timeParts(seconds: Long): List<Pair<String, String>> {
        val h = seconds / 3600
        val m = seconds / 60 % 60
        return listOfNotNull(("$h" to "h").takeIf { h > 0 }, ("$m" to "min").takeIf { m > 0 || h == 0L })
    }

    /** [timeParts] in one line: "1 h 15 min", "45 min". */
    fun timeValue(seconds: Long): String = timeParts(seconds).joinToString(" ") { (n, u) -> "$n $u" }

    /** Minus or plus ([up]) on a time: to the next 5 minutes, from 5 min to [MAX_HOURS] h. */
    fun stepTime(seconds: Long, up: Boolean): Long {
        val step = 300L
        val next = if (up) (seconds / step + 1) * step else (seconds - 1) / step * step
        return next.coerceIn(step, MAX_HOURS * 3600L)
    }

    const val MAX_HOURS = 10

    /** Typed hours and minutes as seconds, either may be blank: 1 min to [MAX_HOURS] h; null for anything else. */
    fun parseTime(hours: String, minutes: String): Long? {
        val h = hours.trim().ifEmpty { "0" }.toLongOrNull() ?: return null
        val m = minutes.trim().ifEmpty { "0" }.toLongOrNull() ?: return null
        return (h * 3600 + m * 60).takeIf { it in 60..MAX_HOURS * 3600L }
    }

    /** The time presets under the value: 30 and 45 min, 1 h and 1 h 30, with their labels. */
    val TIME_PRESETS: List<Pair<Long, String>> = listOf(1800L to "30 min", 2700L to "45 min", 3600L to "1 h", 5400L to "1 h 30")

    /** A time goal's note: when it would end if you started now, "Ends near 19:15". */
    fun endsNear(now: java.time.LocalTime, seconds: Long, is24Hour: Boolean): String =
        "Ends near " + now.plusSeconds(seconds).format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault()))

    /** While a time is typed: "Type hours, then minutes. Ends near 19:15". */
    fun timeTypingNote(endsNear: String): String = "Type hours, then minutes. $endsNear"

    /** How the zone buttons work, and what the strap does. */
    const val ZONE_NOTE = "Tap a zone, or a second one for a range. The strap buzzes when you leave it."

    const val YOUR_ZONES = "Your zones"

    /**
     * A zone goal's heart rates in [zones] at [maxHr], with the zones' names: "115 to 152 bpm, aerobic to tempo",
     * "115 to 133 bpm, aerobic", and up from zone 5: "172 bpm and up, max".
     */
    fun zoneRangeLine(goal: WorkoutTarget.Zones, zones: org.openstrap.app.data.HeartZones, maxHr: Int): String {
        val from = zones.startBpm(goal.low, maxHr)
        val bpm = zones.endBpm(goal.high, maxHr)?.let { "$from to $it bpm" } ?: "$from bpm and up"
        val names = if (goal.low == goal.high) ZONE_NAMES[goal.low].lowercase() else "${ZONE_NAMES[goal.low].lowercase()} to ${ZONE_NAMES[goal.high].lowercase()}"
        return "$bpm, $names"
    }

    /** Run intervals' presets on Ready: 8 × 200 m, 6 × 400 m, 5 × 1 km and 4 × 4 min, each with its rest. */
    val INTERVAL_PRESETS: List<org.openstrap.app.data.Structure.Reps> = listOf(
        org.openstrap.app.data.Structure.Reps(200, null, 60, 8),
        org.openstrap.app.data.Structure.Reps(400, null, 90, 6),
        org.openstrap.app.data.Structure.Reps(1000, null, 120, 5),
        org.openstrap.app.data.Structure.Reps(null, 240, 180, 4),
    )

    /** What run intervals open on before you've chosen any: 6 × 400 m with 90 s rest. */
    val DEFAULT_INTERVALS: org.openstrap.app.data.Structure.Reps = INTERVAL_PRESETS[1]

    /** A rep's length as a number and its unit: "400" "m", "1.5" "km", "4" "min", "90" "s". */
    fun repParts(r: org.openstrap.app.data.Structure.Reps): Pair<String, String> = when {
        r.meters != null -> if (r.meters >= 1000) distanceValue(r.meters) to "km" else "${r.meters}" to "m"
        (r.seconds ?: 0) % 60 == 0L -> "${(r.seconds ?: 0) / 60}" to "min"
        else -> "${r.seconds}" to "s"
    }

    /** The rest between reps as a number and its unit: "90" "s", "2" "min", "150" "s". */
    fun restParts(seconds: Long): Pair<String, String> = if (seconds >= 120 && seconds % 60 == 0L) "${seconds / 60}" to "min" else "$seconds" to "s"

    /**
     * What run intervals do: "The strap buzzes at the end of each 400 m and when rest is up. 2.4 km in all.", or for
     * reps of a time "at the end of each 4 min rep", with "16 min of reps in all".
     */
    fun intervalsNote(r: org.openstrap.app.data.Structure.Reps): String {
        val (n, unit) = repParts(r)
        val each = if (r.meters != null) "each $n $unit" else "each $n $unit rep"
        val total = r.meters?.let { m ->
            val all = m * r.rounds
            if (all >= 1000) "${distanceValue(all)} km in all" else "$all m in all"
        } ?: "${timeValue((r.seconds ?: 0) * r.rounds)} of reps in all"
        return "The strap buzzes at the end of $each and when rest is up. $total."
    }

    const val INTERVALS_TYPING = "How many, then each rep, then the rest"
    const val TAP_A_NUMBER = "Tap a number to type it"
    const val EACH_REP_IN = "Each rep in"

    /** The units a rep's length is typed in. */
    enum class RepUnit(val label: String) { Meters("m"), Kilometers("km"), Minutes("min") }

    /** The unit [r] is shown in, to type it in the same one. */
    fun repUnit(r: org.openstrap.app.data.Structure.Reps): RepUnit = when {
        r.meters != null -> if (r.meters >= 1000) RepUnit.Kilometers else RepUnit.Meters
        else -> RepUnit.Minutes
    }

    /** How many reps: 1 to [MAX_REPS]. */
    fun parseCount(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_REPS }

    const val MAX_REPS = 30

    /** A typed rep length in [unit] as [r] with it: 50 m to 50 km, or 10 s to 60 min; null for anything else. */
    fun parseRep(text: String, unit: RepUnit, r: org.openstrap.app.data.Structure.Reps): org.openstrap.app.data.Structure.Reps? {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return when (unit) {
            RepUnit.Meters -> v.roundToInt().takeIf { it in 50..50_000 }?.let { r.copy(meters = it, seconds = null) }
            RepUnit.Kilometers -> (v * 1000).roundToInt().takeIf { it in 50..50_000 }?.let { r.copy(meters = it, seconds = null) }
            RepUnit.Minutes -> (v * 60).roundToInt().toLong().takeIf { it in 10..3600 }?.let { r.copy(meters = null, seconds = it) }
        }
    }

    /** Typed rest in seconds, none to 15 min. */
    fun parseRest(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it in 0..900 }

    /** Saving the reps by name, to find them on Choose and here. */
    const val SAVE_INTERVALS = "Save"
    const val SAVED_INTERVALS = "Saved"
    const val SAVE_NOTE = "To find on Choose and here. Up to ten are kept."
    const val NAME_HINT = "Name, like Tuesday intervals"

    /** Ready's switch rows, under the where row. */
    const val ASK_TO_END = "Ask to end when I stop"
    const val ASK_TO_END_NOTE = "The strap buzzes after 8 min still"
    const val KEEP_ROUTE = "Keep the route"
    const val TURN_ON_LOCATION = "Turn on location"
}
