package org.openstrap.app.ui.workout

import androidx.compose.ui.graphics.vector.ImageVector
import org.openstrap.app.data.Route
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.SportTypes
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.theme.AppIcons
import java.time.LocalDate
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
        type in SportTypes.RUNS -> "Just run"
        type in SportTypes.WALKS -> "Just walk"
        Workouts.tileType(type) == 4 -> "Just ride"
        else -> "Just go"
    }

    /** No plan: strength's open sets, or mind and body with no length. */
    const val OPEN = "Open"

    /** Above Ready's plan card, by family: "Goal", "Plan", "Length", "Match". */
    fun planTitle(family: Workouts.Family): String = when (family) {
        Workouts.Family.Distance, Workouts.Family.IndoorCardio -> "Goal"
        Workouts.Family.Strength, Workouts.Family.Intervals -> "Plan"
        Workouts.Family.MindBody -> "Length"
        Workouts.Family.Games -> "Match"
    }

    /** The plan pill's sheet title, and what the pill says it does to a screen reader. */
    fun planSheet(family: Workouts.Family): String = if (family == Workouts.Family.Games) "Free play or periods" else "Choose a ${planTitle(family).lowercase()}"
    fun planHint(family: Workouts.Family): String = "Change the ${planTitle(family).lowercase()}"

    /** A typed number in [range]; null for anything else. */
    fun parseNumber(text: String, range: LongRange): Long? = text.trim().toLongOrNull()?.takeIf { it in range }

    /* -------------------------------------------- Strength's plan ------------------------------------------- */

    const val PLAN = "Plan"
    const val STRENGTH_STATUS = "The strap buzzes when rest is over, so the phone can stay on the bench."
    const val OPEN_SETS_KIND_NOTE = "As many sets as you like, rest after each"
    const val PLAN_KIND_NOTE = "A number of sets with rest between"
    const val OPEN_SETS_NOTE = "As many sets as you like. The strap buzzes when rest is over."
    const val STRENGTH_TYPING = "How many sets, then the rest in seconds"

    /** The sets presets under the plan. */
    val SET_PRESETS: List<Pair<Int, String>> = (3..6).map { it to "$it sets" }

    /** What the plan opens on when there's none from last time. */
    const val DEFAULT_SETS = 5

    /** Typed sets, within [Strength.SETS]. */
    fun parseSets(text: String): Int? = parseNumber(text, Strength.SETS.first.toLong()..Strength.SETS.last.toLong())?.toInt()

    /** Typed rest in seconds, within [Strength.Rest.RANGE_S]. */
    fun parseSetRest(text: String): Long? = parseNumber(text, Strength.Rest.RANGE_S)

    const val END_REST_EARLY = "End rest early"

    /** End rest early's note, with the level it ends at: "Once you're under 110 bpm". */
    fun endRestNote(bpm: Int): String = "Once you're under $bpm bpm"

    /** The level End rest early turns on at when none was chosen before. */
    const val DEFAULT_BELOW = 110

    const val LOG_SETS = "Log reps and weight"
    const val LOG_SETS_NOTE = "A quick card after each set"

    const val GAMES_STATUS = "Phone in your bag? That's fine."

    /** The GPS line on Ready for an outdoor sport: finding a fix with its accuracy, then ready with it ("GPS ready, 6 m"). */
    fun gpsStatus(accuracy: Float?): String = when {
        accuracy == null || accuracy > 1000 -> "Finding GPS"
        accuracy > Route.MAX_ACCURACY_M -> "Finding GPS, ±${accuracy.toInt()} m"
        else -> "GPS ready, ${accuracy.roundToInt().coerceAtLeast(1)} m"
    }

    /** Under two minutes in seconds ("90 s"), then minutes: "2 min", "2 min 30 s". */
    fun restText(seconds: Long): String = when {
        seconds < 120 -> "$seconds s"
        seconds % 60 == 0L -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    /**
     * One of HIIT's presets on Ready: its rounds (null for Free), its name and what it is in the plan pill's sheet, and
     * its icon there, null for the sport's own. Each carries its icon, so a new preset can't miss one.
     */
    class HiitPreset(val rounds: org.openstrap.app.data.Structure?, val name: String, val note: String, val icon: ImageVector?)

    /** HIIT's presets on Ready, Free last. */
    val PRESETS: List<HiitPreset> = listOf(
        HiitPreset(org.openstrap.app.data.Structure.TABATA, "Tabata", "20 s on, 10 off, 8 rounds", AppIcons.Timer),
        HiitPreset(org.openstrap.app.data.Structure.FORTY_TWENTY, "40 / 20", "10 rounds", AppIcons.Hourglass),
        HiitPreset(org.openstrap.app.data.Structure.EVERY_MINUTE, "Every minute", "12 rounds, rest is what's left", AppIcons.Alarm),
        HiitPreset(null, "Free", "No timer, laps mark the rounds", null),
    )

    const val INTERVALS_STATUS = "The strap buzzes at every switch, so the phone can stay on the floor."
    const val HIIT_FREE_NOTE = "No timer. Laps mark the rounds."
    const val HIIT_TYPING = "Work, then rest, then how many rounds"
    const val EVERY_TYPING = "Each round's time, then how many rounds"
    const val WARM_UP = "Warm up first"
    const val WARM_UP_NOTE = "No timer until you start the rounds."

    /** HIIT's plan in its pill: a preset's name, else what it is ("30 / 15", "Every 90 s"). */
    fun hiitName(s: org.openstrap.app.data.Structure): String {
        PRESETS.firstOrNull { it.rounds?.code == s.warm(false).code }?.let { return it.name }
        return when (s) {
            is org.openstrap.app.data.Structure.Timed -> "${s.work} / ${s.rest}"
            is org.openstrap.app.data.Structure.EveryMinute -> if (s.period == 60L) "Every minute" else "Every ${restText(s.period)}"
            else -> structureName(s)
        }
    }

    /** All of a timed structure's time, in a line beside its rounds: "4:00 in all"; null where it has none. */
    fun totalLine(s: org.openstrap.app.data.Structure): String? = totalTime(s)?.let { "${Units.workoutClock(it)} in all" }

    /** Typed work, rest and rounds over [s]: work 5 s to 10 min, rest up to 10 min, 1 to [MAX_ROUNDS]; what doesn't read stays. */
    fun typedTimed(s: org.openstrap.app.data.Structure.Timed, typed: List<String>): org.openstrap.app.data.Structure.Timed = s.copy(
        work = parseNumber(typed[0], 5L..600L) ?: s.work,
        rest = parseNumber(typed[1], 0L..600L) ?: s.rest,
        rounds = parseNumber(typed[2], 1L..MAX_ROUNDS.toLong())?.toInt() ?: s.rounds,
    )

    /** Typed round time and rounds over [s]: 10 s to 10 min a round, 1 to [MAX_ROUNDS]; what doesn't read stays. */
    fun typedEveryMinute(s: org.openstrap.app.data.Structure.EveryMinute, typed: List<String>): org.openstrap.app.data.Structure.EveryMinute = s.copy(
        period = parseNumber(typed[0], 10L..600L) ?: s.period,
        rounds = parseNumber(typed[1], 1L..MAX_ROUNDS.toLong())?.toInt() ?: s.rounds,
    )

    const val MAX_ROUNDS = 50

    /** A structure's name: a preset's ("Tabata"), else what it is ("6 × 400 m", "8 × 2 min", "30 / 15, 6 rounds"). */
    fun structureName(s: org.openstrap.app.data.Structure): String {
        PRESETS.firstOrNull { it.rounds?.code == s.warm(false).code }?.let { return it.name }
        return when (s) {
            is org.openstrap.app.data.Structure.Timed -> "${s.work} / ${s.rest}, ${s.rounds} rounds"
            is org.openstrap.app.data.Structure.EveryMinute -> "Every ${restText(s.period)}, ${s.rounds} rounds"
            // As Ready's rep field and note say it ([repParts]): "6 × 1.5 km", "8 × 1 min".
            is org.openstrap.app.data.Structure.Reps -> repParts(s).let { (n, unit) -> "${s.rounds} × $n $unit" }
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

    /** Mind and body's line on Ready (workouts spec 2.2), with a length and without one. */
    const val MIND_STATUS = "Quiet strap and a dim screen. One soft buzz at the end."
    const val MIND_OPEN_NOTE = "Quiet strap and a dim screen. End it when you're ready."
    const val MIND_OPEN_KIND_NOTE = "No length, end it yourself"
    const val MIND_TIME_KIND_NOTE = "One soft buzz when the time is up"

    /** Mind and body's length presets: 15, 30 and 45 min and 1 h. */
    val MIND_PRESETS: List<Pair<Long, String>> = org.openstrap.app.data.WorkoutSetup.LENGTHS.map { it.seconds to timeValue(it.seconds) }

    /** A game's choice on Ready: free play or its periods. */
    const val FREE_PLAY = "Free play"
    const val FREE_PLAY_KIND_NOTE = "No periods, the clock just runs"
    const val HALVES_KIND_NOTE = "Two halves with half time between"
    const val QUARTERS_KIND_NOTE = "Four quarters with a break between each"
    const val GAMES_TYPING = "How many, how long, then the break"

    /** What a game's strap does: "...The strap buzzes at the end of each half and break.", or nothing in free play. */
    fun gamesNote(p: org.openstrap.app.data.Structure.Periods?): String {
        p ?: return GAMES_STATUS
        val each = when (p.rounds) { 2 -> "half"; 4 -> "quarter"; else -> "period" }
        return "$GAMES_STATUS The strap buzzes at the end of each $each" + if (p.rest > 0 && p.rounds > 1) " and break." else "."
    }

    /** Typed periods, minutes long and break minutes over [p]: 2 to 8 periods of 1 to 90 min, a break up to 30 min. */
    fun typedPeriods(p: org.openstrap.app.data.Structure.Periods, typed: List<String>): org.openstrap.app.data.Structure.Periods = p.copy(
        rounds = parseNumber(typed[0], 2L..8L)?.toInt() ?: p.rounds,
        length = parseNumber(typed[1], 1L..90L)?.let { it * 60 } ?: p.length,
        rest = parseNumber(typed[2], 0L..30L)?.let { it * 60 } ?: p.rest,
    )

    /** All of a timed structure's time, rounds of work and the rests between, for Ready: "4:00". */
    fun totalTime(s: org.openstrap.app.data.Structure): Long? = when (s) {
        is org.openstrap.app.data.Structure.Timed -> s.rounds * s.work + (s.rounds - 1) * s.rest
        is org.openstrap.app.data.Structure.EveryMinute -> s.rounds * s.period
        is org.openstrap.app.data.Structure.Reps -> null
        is org.openstrap.app.data.Structure.Periods -> s.rounds * s.length + (s.rounds - 1) * s.rest
    }

    /**
     * Your usual's line: what you did last time and when ("Last time 5.2 km, Tuesday"): the distance for a distance
     * sport that has one, the sets for strength, else the minutes.
     */
    fun lastTime(a: WorkoutAnalysis, today: LocalDate, zone: java.time.ZoneId, locale: Locale = Locale.getDefault()): String {
        val what = when {
            a.distanceMeters != null && a.distanceMeters >= 100 && Workouts.family(a.workout.sportType) == Workouts.Family.Distance ->
                Units.distance(a.distanceMeters, Units.Precision.Workout, locale = locale).orEmpty()
            a.sets != null && a.sets > 0 -> if (a.sets == 1) "1 set" else "${a.sets} sets"
            else -> "${(a.activeSeconds / 60).coerceAtLeast(1)} min"
        }
        val day = java.time.Instant.ofEpochSecond(a.workout.start).atZone(zone).toLocalDate()
        return "Last time $what, ${DayWords.inSentence(day, today, locale)}"
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
        type in SportTypes.RUNS -> "Pace from your steps"
        type in SportTypes.ON_FOOT -> "Distance from your steps"
        else -> "Time and your heart, no distance"
    }

    private const val TREADMILL = 2
    private const val POOL = 6
    private const val OPEN_WATER = 7

    /* -------------------------------------------- Ready's goal ---------------------------------------------- */

    /** The goal kinds' names after the sport's [noGoal], in the goal pill and its sheet. */
    const val DISTANCE = "Distance"
    const val TIME = "Time"
    const val ZONE = "Zone"
    const val INTERVALS = "Intervals"

    const val TAP_TO_TYPE = "Tap to type"

    /** Under each goal kind in the pill's sheet, what the strap does with it. */
    const val DISTANCE_KIND_NOTE = "A buzz at halfway and at the goal"
    const val TIME_KIND_NOTE = "A buzz when the time is up"
    const val ZONE_KIND_NOTE = "A buzz when you leave your zone"
    const val INTERVALS_KIND_NOTE = "Repeats with rest between, a buzz at each switch"

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

    /** A time goal in one line, as every duration reads ([Units.duration]): "1 h 15", "1 h", "45 min". */
    fun timeValue(seconds: Long): String = Units.duration(seconds)

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
        "Ends near " + TimeOfDay.format(now.plusSeconds(seconds), is24Hour)

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
    /** The route switch, named for what it gives you: "Save a map of my run", "...of my walk", "...of my ride". */
    fun keepRoute(type: Int): String {
        val name = Workouts.shortName(type).lowercase()
        val noun = when {
            "cycl" in name || "bik" in name -> "ride"
            name == "skiing" -> "ski"
            name == "rucking" -> "ruck"
            else -> name
        }
        return "Save a map of my $noun"
    }
    const val TURN_ON_LOCATION = "Turn on location"
}
