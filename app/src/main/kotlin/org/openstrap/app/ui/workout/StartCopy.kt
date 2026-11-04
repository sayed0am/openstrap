package org.openstrap.app.ui.workout

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import org.openstrap.app.R
import org.openstrap.app.data.Route
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutAnalysis
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.SportTypes
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.DayWords
import org.openstrap.app.presentation.TimeOfDay
import org.openstrap.app.presentation.Units
import org.openstrap.app.ui.theme.AppIcons
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import org.openstrap.app.presentation.WorkoutCopy

/*
 * What Choose and Ready say (workouts spec 2.1, 2.2, 7): the sport, the goal and what the strap will do, never the
 * family. Pure, so it's tested without the screens. The text is in res/values/strings_workout_start.xml.
 */
object StartCopy {
    /** The strap's one line on Choose: "Strap ready, 72 bpm", or what's wrong. */
    fun strapLine(connection: ConnectionState, bpm: Int?, strings: Strings = Strings.app): String = when (connection) {
        is ConnectionState.Connected ->
            bpm?.let { strings.format(R.string.workout_start_strap_ready_bpm, it) } ?: strings.get(R.string.workout_start_strap_ready)
        ConnectionState.Connecting, ConnectionState.Authenticating -> strings.get(R.string.workout_start_strap_connecting)
        ConnectionState.NotPaired -> strings.get(R.string.workout_start_strap_not_paired)
        ConnectionState.Disconnected, is ConnectionState.Failed -> strings.get(R.string.workout_start_strap_not_connected)
    }

    /** The goal chip for no goal, named for the sport: "Just run", "Just walk", "Just ride", else "Just go". */
    fun noGoal(type: Int, strings: Strings = Strings.app): String = strings.get(
        when {
            type in SportTypes.RUNS -> R.string.workout_start_just_run
            type in SportTypes.WALKS -> R.string.workout_start_just_walk
            Workouts.tileType(type) == 4 -> R.string.workout_start_just_ride
            else -> R.string.workout_start_just_go
        },
    )

    /** No plan: strength's open sets, or mind and body with no length. */
    val OPEN: String get() = Strings.app.get(R.string.workout_start_open)

    /** Above Ready's plan card, by family: "Goal", "Plan", "Length", "Match". */
    fun planTitle(family: Workouts.Family, strings: Strings = Strings.app): String = strings.get(
        when (family) {
            Workouts.Family.Distance, Workouts.Family.IndoorCardio -> R.string.workout_start_plan_title_goal
            Workouts.Family.Strength, Workouts.Family.Intervals -> R.string.workout_start_plan_title_plan
            Workouts.Family.MindBody -> R.string.workout_start_plan_title_length
            Workouts.Family.Games -> R.string.workout_start_plan_title_match
        },
    )

    /** The plan pill's sheet title, and what the pill says it does to a screen reader. */
    fun planSheet(family: Workouts.Family, strings: Strings = Strings.app): String = strings.get(
        when (family) {
            Workouts.Family.Distance, Workouts.Family.IndoorCardio -> R.string.workout_start_plan_sheet_goal
            Workouts.Family.Strength, Workouts.Family.Intervals -> R.string.workout_start_plan_sheet_plan
            Workouts.Family.MindBody -> R.string.workout_start_plan_sheet_length
            Workouts.Family.Games -> R.string.workout_start_plan_sheet_games
        },
    )
    fun planHint(family: Workouts.Family, strings: Strings = Strings.app): String = strings.get(
        when (family) {
            Workouts.Family.Distance, Workouts.Family.IndoorCardio -> R.string.workout_start_plan_hint_goal
            Workouts.Family.Strength, Workouts.Family.Intervals -> R.string.workout_start_plan_hint_plan
            Workouts.Family.MindBody -> R.string.workout_start_plan_hint_length
            Workouts.Family.Games -> R.string.workout_start_plan_hint_match
        },
    )

    /** A typed number in [range]; null for anything else. */
    fun parseNumber(text: String, range: LongRange): Long? = text.trim().toLongOrNull()?.takeIf { it in range }

    /* -------------------------------------------- Strength's plan ------------------------------------------- */

    val PLAN: String get() = Strings.app.get(R.string.workout_start_strength_plan)
    val STRENGTH_STATUS: String get() = Strings.app.get(R.string.workout_start_strength_status)
    val OPEN_SETS_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_open_sets_kind_note)
    val PLAN_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_plan_kind_note)
    val OPEN_SETS_NOTE: String get() = Strings.app.get(R.string.workout_start_open_sets_note)
    val STRENGTH_TYPING: String get() = Strings.app.get(R.string.workout_start_strength_typing)

    /** The sets presets under the plan. */
    val SET_PRESETS: List<Pair<Int, String>> get() = (3..6).map { it to Strings.app.plural(R.plurals.workout_start_sets, it, it) }

    /** What the plan opens on when there's none from last time. */
    const val DEFAULT_SETS = 5

    /** Typed sets, within [Strength.SETS]. */
    fun parseSets(text: String): Int? = parseNumber(text, Strength.SETS.first.toLong()..Strength.SETS.last.toLong())?.toInt()

    /** Typed rest in seconds, within [Strength.Rest.RANGE_S]. */
    fun parseSetRest(text: String): Long? = parseNumber(text, Strength.Rest.RANGE_S)

    val END_REST_EARLY: String get() = Strings.app.get(R.string.workout_start_end_rest_early)

    /** End rest early's note, with the level it ends at: "Once you're under 110 bpm". */
    fun endRestNote(bpm: Int, strings: Strings = Strings.app): String = strings.format(R.string.workout_start_end_rest_note, bpm)

    /** The level End rest early turns on at when none was chosen before. */
    const val DEFAULT_BELOW = 110

    val LOG_SETS: String get() = Strings.app.get(R.string.workout_start_log_sets)
    val LOG_SETS_NOTE: String get() = Strings.app.get(R.string.workout_start_log_sets_note)

    val GAMES_STATUS: String get() = Strings.app.get(R.string.workout_start_games_status)

    /** The GPS line on Ready for an outdoor sport: finding a fix with its accuracy, then ready with it ("GPS ready, 6 m"). */
    fun gpsStatus(accuracy: Float?, strings: Strings = Strings.app): String = when {
        accuracy == null || accuracy > 1000 -> strings.get(R.string.workout_start_gps_finding)
        accuracy > Route.MAX_ACCURACY_M -> strings.format(R.string.workout_start_gps_finding_accuracy, accuracy.toInt())
        else -> strings.format(R.string.workout_start_gps_ready, accuracy.roundToInt().coerceAtLeast(1))
    }

    /** Under two minutes in seconds ("90 s"), then minutes: "2 min", "2 min 30 s". */
    fun restText(seconds: Long, strings: Strings = Strings.app): String = when {
        seconds < 120 -> strings.format(R.string.workout_start_seconds, seconds)
        seconds % 60 == 0L -> strings.format(R.string.workout_start_minutes, seconds / 60)
        else -> strings.format(R.string.workout_start_minutes_seconds, seconds / 60, seconds % 60)
    }

    /**
     * One of HIIT's presets on Ready: its rounds (null for Free), its name and what it is in the plan pill's sheet, and
     * its icon there, null for the sport's own. Each carries its icon, so a new preset can't miss one.
     */
    class HiitPreset(val rounds: org.openstrap.app.data.Structure?, val name: String, val note: String, val icon: ImageVector?)

    /** HIIT's presets on Ready, Free last. */
    val PRESETS: List<HiitPreset> get() = presets(Strings.app)

    private fun presets(strings: Strings): List<HiitPreset> = listOf(
        HiitPreset(
            org.openstrap.app.data.Structure.TABATA, strings.get(R.string.workout_start_preset_tabata),
            strings.get(R.string.workout_start_preset_tabata_note), AppIcons.Timer,
        ),
        HiitPreset(
            org.openstrap.app.data.Structure.FORTY_TWENTY, strings.get(R.string.workout_start_preset_forty_twenty),
            strings.get(R.string.workout_start_preset_forty_twenty_note), AppIcons.Hourglass,
        ),
        HiitPreset(
            org.openstrap.app.data.Structure.EVERY_MINUTE, strings.get(R.string.workout_start_every_minute),
            strings.get(R.string.workout_start_preset_every_minute_note), AppIcons.Alarm,
        ),
        HiitPreset(null, strings.get(R.string.workout_start_preset_free), strings.get(R.string.workout_start_preset_free_note), null),
    )

    val INTERVALS_STATUS: String get() = Strings.app.get(R.string.workout_start_intervals_status)
    val HIIT_FREE_NOTE: String get() = Strings.app.get(R.string.workout_start_hiit_free_note)
    val HIIT_TYPING: String get() = Strings.app.get(R.string.workout_start_hiit_typing)
    val EVERY_TYPING: String get() = Strings.app.get(R.string.workout_start_every_typing)
    val WARM_UP: String get() = Strings.app.get(R.string.workout_start_warm_up)
    val WARM_UP_NOTE: String get() = Strings.app.get(R.string.workout_start_warm_up_note)

    /** HIIT's plan in its pill: a preset's name, else what it is ("30 / 15", "Every 90 s"). */
    fun hiitName(s: org.openstrap.app.data.Structure, strings: Strings = Strings.app): String {
        presets(strings).firstOrNull { it.rounds?.code == s.warm(false).code }?.let { return it.name }
        return when (s) {
            is org.openstrap.app.data.Structure.Timed -> strings.format(R.string.workout_start_work_rest, s.work, s.rest)
            is org.openstrap.app.data.Structure.EveryMinute ->
                if (s.period == 60L) {
                    strings.get(R.string.workout_start_every_minute)
                } else {
                    strings.format(R.string.workout_start_every, restText(s.period, strings))
                }
            else -> structureName(s, strings)
        }
    }

    /** All of a timed structure's time, in a line beside its rounds: "4:00 in all"; null where it has none. */
    fun totalLine(s: org.openstrap.app.data.Structure, strings: Strings = Strings.app): String? =
        totalTime(s)?.let { strings.format(R.string.workout_start_total_line, Units.workoutClock(it)) }

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
    fun structureName(s: org.openstrap.app.data.Structure, strings: Strings = Strings.app): String {
        presets(strings).firstOrNull { it.rounds?.code == s.warm(false).code }?.let { return it.name }
        return when (s) {
            is org.openstrap.app.data.Structure.Timed -> strings.format(R.string.workout_start_structure_timed, s.work, s.rest, s.rounds)
            is org.openstrap.app.data.Structure.EveryMinute ->
                strings.format(R.string.workout_start_structure_every, restText(s.period, strings), s.rounds)
            // As Ready's rep field and note say it ([repParts]): "6 × 1.5 km", "8 × 1 min".
            is org.openstrap.app.data.Structure.Reps ->
                strings.format(R.string.workout_start_structure_reps, s.rounds, repLength(s, strings))
            is org.openstrap.app.data.Structure.Periods -> periodsName(s, strings)
        }
    }

    /** A rep's length with its unit, as [repParts] splits it: "400 m", "1.5 km", "4 min", "90 s". */
    private fun repLength(r: org.openstrap.app.data.Structure.Reps, strings: Strings): String = when {
        r.meters != null -> if (r.meters >= 1000) {
            strings.format(R.string.workout_start_km, distanceValue(r.meters))
        } else {
            strings.format(R.string.workout_start_meters, r.meters)
        }
        (r.seconds ?: 0) % 60 == 0L -> strings.format(R.string.workout_start_minutes, (r.seconds ?: 0) / 60)
        else -> strings.format(R.string.workout_start_seconds, r.seconds ?: 0)
    }

    /** "Halves", "Quarters" or "3 periods". */
    fun periodsName(s: org.openstrap.app.data.Structure.Periods, strings: Strings = Strings.app): String = when (s.rounds) {
        2 -> strings.get(R.string.workout_start_halves)
        4 -> strings.get(R.string.workout_start_quarters)
        else -> strings.format(R.string.workout_start_periods, s.rounds)
    }

    /**
     * A period by its place: "1st half", "3rd quarter", "Period 2". Each half and quarter is its own string, since
     * languages put the place in the word differently. A game never has more of them than it was set up with, so past
     * those it reads as a numbered period.
     */
    fun periodName(index: Int, of: Int, strings: Strings = Strings.app): String {
        val n = index + 1
        val id = when (of) {
            2 -> HALVES.getOrNull(index)
            4 -> QUARTERS.getOrNull(index)
            else -> null
        }
        return if (id != null) strings.get(id) else strings.format(R.string.workout_start_period_n, n)
    }

    private val HALVES = listOf(R.string.workout_start_half_1, R.string.workout_start_half_2)
    private val QUARTERS = listOf(
        R.string.workout_start_quarter_1, R.string.workout_start_quarter_2,
        R.string.workout_start_quarter_3, R.string.workout_start_quarter_4,
    )

    /** What the break between periods is called. */
    fun breakName(of: Int, strings: Strings = Strings.app): String =
        strings.get(if (of == 2) R.string.workout_start_half_time else R.string.workout_start_break)

    /** Mind and body's line on Ready (workouts spec 2.2), with a length and without one. */
    val MIND_STATUS: String get() = Strings.app.get(R.string.workout_start_mind_status)
    val MIND_OPEN_NOTE: String get() = Strings.app.get(R.string.workout_start_mind_open_note)
    val MIND_OPEN_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_mind_open_kind_note)
    val MIND_TIME_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_mind_time_kind_note)

    /** Mind and body's length presets: 15, 30 and 45 min and 1 h. */
    val MIND_PRESETS: List<Pair<Long, String>>
        get() = org.openstrap.app.data.WorkoutSetup.LENGTHS.map { it.seconds to timeValue(it.seconds) }

    /** A game's choice on Ready: free play or its periods. */
    val FREE_PLAY: String get() = Strings.app.get(R.string.workout_start_free_play)
    val FREE_PLAY_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_free_play_kind_note)
    val HALVES_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_halves_kind_note)
    val QUARTERS_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_quarters_kind_note)
    val GAMES_TYPING: String get() = Strings.app.get(R.string.workout_start_games_typing)

    /** What a game's strap does: "...The strap buzzes at the end of each half and break.", or nothing in free play. */
    fun gamesNote(p: org.openstrap.app.data.Structure.Periods?, strings: Strings = Strings.app): String {
        p ?: return strings.get(R.string.workout_start_games_status)
        val breaks = p.rest > 0 && p.rounds > 1
        return strings.get(
            when (p.rounds) {
                2 -> if (breaks) R.string.workout_start_games_note_half_break else R.string.workout_start_games_note_half
                4 -> if (breaks) R.string.workout_start_games_note_quarter_break else R.string.workout_start_games_note_quarter
                else -> if (breaks) R.string.workout_start_games_note_period_break else R.string.workout_start_games_note_period
            },
        )
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
    fun lastTime(
        a: WorkoutAnalysis, today: LocalDate, zone: java.time.ZoneId, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app,
    ): String {
        val what = when {
            a.distanceMeters != null && a.distanceMeters >= 100 && Workouts.family(a.workout.sportType) == Workouts.Family.Distance ->
                Units.distance(a.distanceMeters, Units.Precision.Workout, locale = locale).orEmpty()
            a.sets != null && a.sets > 0 -> strings.plural(R.plurals.workout_start_sets, a.sets, a.sets)
            else -> strings.format(R.string.workout_start_minutes, (a.activeSeconds / 60).coerceAtLeast(1))
        }
        val day = java.time.Instant.ofEpochSecond(a.workout.start).atZone(zone).toLocalDate()
        return strings.format(R.string.workout_start_last_time, what, DayWords.inSentence(day, today, locale))
    }

    /* -------------------------------------------- Ready's where --------------------------------------------- */

    /** Start on Ready, naming the sport: "Start run", and "Start treadmill run" on the treadmill. */
    fun startLabel(type: Int, strings: Strings = Strings.app): String =
        if (type == TREADMILL) strings.get(R.string.workout_start_start_treadmill) else WorkoutCopy.startLabel(type)

    /** Where row's title for the side you're on: "Outside", "On a treadmill", "Indoors", "Open water", "In a pool". */
    fun place(sides: Workouts.Sides, indoor: Boolean, strings: Strings = Strings.app): String = strings.get(
        when {
            !indoor -> if (sides.outdoor == OPEN_WATER) R.string.workout_start_place_open_water else R.string.workout_start_place_outside
            sides.indoor == TREADMILL -> R.string.workout_start_place_treadmill
            sides.indoor == POOL -> R.string.workout_start_place_pool
            else -> R.string.workout_start_place_indoors
        },
    )

    /** The where row's pill, naming the other side: "Treadmill", "Outside", "Indoors", "Pool", "Open water". */
    fun otherPlace(sides: Workouts.Sides, indoor: Boolean, strings: Strings = Strings.app): String = strings.get(
        when {
            indoor -> if (sides.outdoor == OPEN_WATER) R.string.workout_start_other_open_water else R.string.workout_start_other_outside
            sides.indoor == TREADMILL -> R.string.workout_start_other_treadmill
            sides.indoor == POOL -> R.string.workout_start_other_pool
            else -> R.string.workout_start_other_indoors
        },
    )

    /** Under an indoor side's title: what you get without GPS. */
    fun indoorNote(type: Int, strings: Strings = Strings.app): String = strings.get(
        when {
            type in SportTypes.RUNS -> R.string.workout_start_indoor_note_run
            type in SportTypes.ON_FOOT -> R.string.workout_start_indoor_note_on_foot
            else -> R.string.workout_start_indoor_note_other
        },
    )

    private const val TREADMILL = 2
    private const val CYCLING = 4
    private const val INDOOR_CYCLING = 8
    private const val SKIING = 12
    private const val RUCKING = 225
    private const val POOL = 6
    private const val OPEN_WATER = 7

    /* -------------------------------------------- Ready's goal ---------------------------------------------- */

    /** The goal kinds' names after the sport's [noGoal], in the goal pill and its sheet. */
    val DISTANCE: String get() = Strings.app.get(R.string.workout_start_kind_distance)
    val TIME: String get() = Strings.app.get(R.string.workout_start_kind_time)
    val ZONE: String get() = Strings.app.get(R.string.workout_start_kind_zone)
    val INTERVALS: String get() = Strings.app.get(R.string.workout_start_kind_intervals)

    val TAP_TO_TYPE: String get() = Strings.app.get(R.string.workout_start_tap_to_type)

    /** Under each goal kind in the pill's sheet, what the strap does with it. */
    val DISTANCE_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_distance_kind_note)
    val TIME_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_time_kind_note)
    val ZONE_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_zone_kind_note)
    val INTERVALS_KIND_NOTE: String get() = Strings.app.get(R.string.workout_start_intervals_kind_note)

    /** No goal's note: what's recorded, the distance only where there is one. */
    fun noGoalNote(hasDistance: Boolean, strings: Strings = Strings.app): String =
        strings.get(if (hasDistance) R.string.workout_start_no_goal_note_distance else R.string.workout_start_no_goal_note)

    /** A distance goal's value in km, as typed: "5", "7.5", "12.45", and a half marathon "21.1". */
    fun distanceValue(meters: Int): String =
        if (meters % 1000 == 0) "${meters / 1000}" else String.format(Locale.ROOT, "%.2f", meters / 1000.0).trimEnd('0').trimEnd('.')

    /** What a distance goal does: "The strap buzzes at halfway and at 7.5 km." */
    fun distanceNote(meters: Int, strings: Strings = Strings.app): String =
        strings.format(R.string.workout_start_distance_note, distanceValue(meters))

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
    val DISTANCE_PRESETS: List<Pair<Int, String>>
        get() = listOf(3000 to "3", 5000 to "5", 10_000 to "10", HALF_MARATHON to Strings.app.get(R.string.workout_start_half_marathon))

    const val HALF_MARATHON = 21_097

    /** A time goal's value as numbers and units: "1" "h" "15" "min", "1" "h", or "45" "min" under an hour. */
    fun timeParts(seconds: Long, strings: Strings = Strings.app): List<Pair<String, String>> {
        val h = seconds / 3600
        val m = seconds / 60 % 60
        return listOfNotNull(
            ("$h" to strings.get(R.string.workout_start_unit_h)).takeIf { h > 0 },
            ("$m" to strings.get(R.string.workout_start_unit_min)).takeIf { m > 0 || h == 0L },
        )
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
    val TIME_PRESETS: List<Pair<Long, String>>
        get() = Strings.app.let { s ->
            listOf(
                1800L to s.format(R.string.workout_start_minutes, 30), 2700L to s.format(R.string.workout_start_minutes, 45),
                3600L to s.format(R.string.workout_start_hours, 1), 5400L to s.format(R.string.workout_start_hours_minutes, 1, 30),
            )
        }

    /** A time goal's note: when it would end if you started now, "Ends near 19:15". */
    fun endsNear(now: java.time.LocalTime, seconds: Long, is24Hour: Boolean, strings: Strings = Strings.app): String =
        strings.format(R.string.workout_start_ends_near, TimeOfDay.format(now.plusSeconds(seconds), is24Hour))

    /** While a time is typed: "Type hours, then minutes. Ends near 19:15". */
    fun timeTypingNote(endsNear: String, strings: Strings = Strings.app): String =
        strings.format(R.string.workout_start_time_typing_note, endsNear)

    /** How the zone buttons work, and what the strap does. */
    val ZONE_NOTE: String get() = Strings.app.get(R.string.workout_start_zone_note)

    val YOUR_ZONES: String get() = Strings.app.get(R.string.workout_start_your_zones)

    /**
     * A zone goal's heart rates in [zones] at [maxHr], with the zones' names: "115 to 152 bpm, aerobic to tempo",
     * "115 to 133 bpm, aerobic", and up from zone 5: "172 bpm and up, max".
     */
    fun zoneRangeLine(
        goal: WorkoutTarget.Zones, zones: org.openstrap.app.data.HeartZones, maxHr: Int, strings: Strings = Strings.app,
    ): String {
        val from = zones.startBpm(goal.low, maxHr)
        val to = zones.endBpm(goal.high, maxHr)
        val low = ZONE_NAMES[goal.low].lowercase()
        val high = ZONE_NAMES[goal.high].lowercase()
        return when {
            to != null && goal.low == goal.high -> strings.format(R.string.workout_start_zone_range_one, from, to, low)
            to != null -> strings.format(R.string.workout_start_zone_range, from, to, low, high)
            goal.low == goal.high -> strings.format(R.string.workout_start_zone_up_one, from, low)
            else -> strings.format(R.string.workout_start_zone_up, from, low, high)
        }
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
    fun repParts(r: org.openstrap.app.data.Structure.Reps, strings: Strings = Strings.app): Pair<String, String> = when {
        r.meters != null -> if (r.meters >= 1000) {
            distanceValue(r.meters) to strings.get(R.string.workout_start_unit_km)
        } else {
            "${r.meters}" to strings.get(R.string.workout_start_unit_m)
        }
        (r.seconds ?: 0) % 60 == 0L -> "${(r.seconds ?: 0) / 60}" to strings.get(R.string.workout_start_unit_min)
        else -> "${r.seconds}" to strings.get(R.string.workout_start_unit_s)
    }

    /** The rest between reps as a number and its unit: "90" "s", "2" "min", "150" "s". */
    fun restParts(seconds: Long, strings: Strings = Strings.app): Pair<String, String> =
        if (seconds >= 120 && seconds % 60 == 0L) {
            "${seconds / 60}" to strings.get(R.string.workout_start_unit_min)
        } else {
            "$seconds" to strings.get(R.string.workout_start_unit_s)
        }

    /**
     * What run intervals do: "The strap buzzes at the end of each 400 m and when rest is up. 2.4 km in all.", or for
     * reps of a time "at the end of each 4 min rep", with "16 min of reps in all".
     */
    fun intervalsNote(r: org.openstrap.app.data.Structure.Reps, strings: Strings = Strings.app): String {
        val each = repLength(r, strings)
        val meters = r.meters ?: return strings.format(
            R.string.workout_start_intervals_note_time, each, timeValue((r.seconds ?: 0) * r.rounds),
        )
        val all = meters * r.rounds
        val total = if (all >= 1000) {
            strings.format(R.string.workout_start_km, distanceValue(all))
        } else {
            strings.format(R.string.workout_start_meters, all)
        }
        return strings.format(R.string.workout_start_intervals_note_distance, each, total)
    }

    val INTERVALS_TYPING: String get() = Strings.app.get(R.string.workout_start_intervals_typing)
    val TAP_A_NUMBER: String get() = Strings.app.get(R.string.workout_start_tap_a_number)
    val EACH_REP_IN: String get() = Strings.app.get(R.string.workout_start_each_rep_in)

    /** The units a rep's length is typed in. */
    enum class RepUnit(@StringRes private val id: Int) {
        Meters(R.string.workout_start_unit_m), Kilometers(R.string.workout_start_unit_km), Minutes(R.string.workout_start_unit_min),
        ;

        val label: String get() = Strings.app.get(id)
    }

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
    val SAVE_INTERVALS: String get() = Strings.app.get(R.string.workout_start_save_intervals)
    val SAVED_INTERVALS: String get() = Strings.app.get(R.string.workout_start_saved_intervals)
    val SAVE_NOTE: String get() = Strings.app.get(R.string.workout_start_save_note)
    val NAME_HINT: String get() = Strings.app.get(R.string.workout_start_name_hint)

    /** Ready's switch rows, under the where row. */
    val ASK_TO_END: String get() = Strings.app.get(R.string.workout_start_ask_to_end)
    val ASK_TO_END_NOTE: String get() = Strings.app.get(R.string.workout_start_ask_to_end_note)
    /** The route switch, named for what it gives you: "Save a map of my run", "...of my walk", "...of my ride". */
    fun keepRoute(type: Int, strings: Strings = Strings.app): String = when (type) {
        // By type rather than by name, so it holds in any language: cycling outdoors and in, skiing and rucking.
        CYCLING, INDOOR_CYCLING -> strings.get(R.string.workout_start_keep_route_ride)
        SKIING -> strings.get(R.string.workout_start_keep_route_ski)
        RUCKING -> strings.get(R.string.workout_start_keep_route_ruck)
        else -> strings.format(R.string.workout_start_keep_route, Workouts.shortName(type).lowercase())
    }
    val TURN_ON_LOCATION: String get() = Strings.app.get(R.string.workout_start_turn_on_location)
}
