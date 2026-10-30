package org.openstrap.app.data

import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.R
import org.openstrap.app.tr

/** A workout with what the strap recorded around it, worked out for display. */
data class WorkoutAnalysis(
    val workout: Workout,
    /** Real end: the strap's sport effect, else three minutes before the last HR sample, else start + exercise time. */
    val end: Long,
    val heartRate: List<WorkoutHeartRate>,
    val effect: SportEffectRow?,
    /** HR at the end minus HR one and two minutes later (bpm); null without the recovery recording. */
    val recovery60: Int?,
    val recovery120: Int?,
    /** HR when the workout ended, the starting point of the recovery figures. */
    val endHeartRate: Int?,
    /** Seconds in each of the five zones (50–60 … 90–100 % of [maxHeartRate]) during the workout. */
    val zoneSeconds: List<Int>,
    val maxHeartRate: Int,
    /** Recorded by OpenStrap from live heart rate rather than by the strap ([WorkoutRecorder]). */
    val fromPhone: Boolean = false,
    /** Minutes the phone missed that the strap's history filled in. */
    val historyMinutes: Int = 0,
    /** Those minutes as runs of epoch seconds, each from its first minute's start to its last minute's end − 1. */
    val historyRanges: List<LongRange> = emptyList(),
    /** Where the workout is shown from: a phone workout's trimmed start, else its start. */
    val shownStart: Long = workout.start,
    /** A phone workout's end before the app or a trim moved it earlier; null when it ends where it was recorded. */
    val recordedEnd: Long? = null,
    /** Recovery time in minutes: the strap's sport effect, or OpenStrap's own for a phone workout ([WorkoutEffect]). */
    val recoveryMinutes: Int? = effect?.recoveryMinutes,
    /** A phone workout's own recovery time, before what earlier ones left ([org.openstrap.app.data.db.AppWorkout.ownRecoveryMinutes]). */
    val ownRecoveryMinutes: Int? = null,
    /** A phone workout's metres, from GPS or estimated from steps ([distanceFromSteps]); the strap stores none. */
    val distanceMeters: Float? = null,
    val distanceFromSteps: Boolean = false,
    /** A phone strength workout's finished sets; null when none were counted, and always for the strap's. */
    val sets: Int? = null,
    /** A phone workout's saved active kcal ([org.openstrap.app.data.db.AppWorkout.activeKcal]); null for the strap's. */
    val activeKcal: Float? = null,
    /** A phone workout's target ([WorkoutTarget.code]); null for none and for the strap's. */
    val target: String? = null,
    /** Active seconds into the shown workout when its kcal target was reached; null when it wasn't, or not inside it. */
    val targetReachedSeconds: Long? = null,
) {
    val durationSeconds: Long get() = end - shownStart

    /**
     * How long the workout was, as every screen shows it: a phone workout's moving time (its shown range less its
     * pauses), a strap workout's start to end, since the strap records no pauses.
     */
    val activeSeconds: Long get() = if (fromPhone) workout.exerciseSeconds?.toLong() ?: durationSeconds else durationSeconds

    /** Recovery HR only says something about fitness when you stopped from a real effort. */
    val recoveryIsMeaningful: Boolean get() = endHeartRate != null && endHeartRate * 100 >= maxHeartRate * MIN_EFFORT_PCT

    private companion object {
        const val MIN_EFFORT_PCT = 65
    }
    val sportName: String get() = Workouts.sportName(workout.sportType)
    /** The strap recognised this workout by itself rather than being started on it (codemap 05 §8). */
    val autoDetected: Boolean get() = !fromPhone && workout.subtype == Workouts.SUBTYPE_AUTO_DETECTED
    /** You started it, on the phone or the strap: not one the strap noticed or filed as "Activity" by itself. */
    val startedByYou: Boolean get() = !autoDetected && workout.sportType != Workouts.GENERIC_ACTIVITY
}

object Workouts {
    /** The strap keeps recording HR this long after a workout, for recovery heart rate. */
    const val RECOVERY_TAIL_S = 180L
    /** A sport effect belongs to a workout when it ends within this long of the workout's start. */
    const val EFFECT_WINDOW_S = 12 * 3600L
    const val ZONES = 5
    /** Summary `Type.subtype` for a workout the strap recognised on its own; 6 is a phone workout it mirrored (codemap 05 §8). */
    const val SUBTYPE_AUTO_DETECTED = 1
    /** Older auto-detected workouts arrive on a first sync of the strap's backlog and get no notification. */
    const val AUTO_DETECT_NOTIFY_WINDOW_S = 24 * 3600L

    /**
     * Whether a strap workout just stored gets Zepp's "detected" notification (codemap 05 §8): only one the strap
     * auto-detected, not stored before (the strap re-sends a workout whose detail failed), and ended within a day.
     */
    fun notifyAutoDetected(w: Workout, storedBefore: Boolean, now: Long): Boolean =
        w.subtype == SUBTYPE_AUTO_DETECTED && !storedBefore && now - w.end <= AUTO_DETECT_NOTIFY_WINDOW_S

    /**
     * Ties a workout to its HR stream and sport effect. [maxHeartRate] comes from the strap's effect when it
     * has one, then [fallbackMaxHr] (the app's usual max HR, [RecoveryEngine.maxHeartRate]).
     */
    fun analyse(w: Workout, hr: List<WorkoutHeartRate>, effects: List<SportEffectRow>, fallbackMaxHr: Int): WorkoutAnalysis {
        val effect = effectOf(w, effects)
        val lastSample = hr.maxOfOrNull { it.timestamp }
        val end = endOf(w, lastSample, effect)
        val maxHr = effect?.maximalHeartRate ?: fallbackMaxHr
        val during = hr.filter { it.timestamp in w.start..end }
        val zones = IntArray(ZONES)
        during.zipWithNext().forEach { (a, b) ->
            val z = zoneOf(a.bpm, maxHr) ?: return@forEach
            zones[z] += (b.timestamp - a.timestamp).coerceAtMost(5).toInt()
        }
        val hasTail = lastSample != null && lastSample >= end + 60
        return WorkoutAnalysis(
            w, end, hr, effect,
            recovery60 = if (hasTail) recovery(hr, end, 60) else null,
            recovery120 = if (lastSample != null && lastSample >= end + 120) recovery(hr, end, 120) else null,
            endHeartRate = medianAt(hr, end),
            zoneSeconds = zones.toList(),
            maxHeartRate = maxHr,
        )
    }

    /** The most of a strap record that may lie outside a phone workout for the two to be one: 10 min, or a quarter. */
    const val SAME_WORKOUT_OUTSIDE_S = 600L
    const val SAME_WORKOUT_OUTSIDE_SHARE = 0.25f

    /** A phone workout's time as [sameWorkout] reads it: its shown start to its end, [now] while it records. */
    fun phoneRange(p: org.openstrap.app.data.db.AppWorkout, now: Long): LongRange = p.from..(p.end ?: now)

    /**
     * Whether the strap workout over [strap] is the strap's own record of the phone workout over [phone], so the two
     * are one workout: the strap auto-detects a walk minutes into it, and mirrors one started on the phone. They
     * overlap, and little of the strap's lies outside: at most [SAME_WORKOUT_OUTSIDE_S], or [SAME_WORKOUT_OUTSIDE_SHARE]
     * of it when longer. So a walk the strap noticed a few minutes before you tapped Start merges, and a gym session
     * that began as your walk ended stays its own, both ways round.
     */
    fun sameWorkout(strap: LongRange, phone: LongRange): Boolean {
        val overlap = minOf(strap.last, phone.last) - maxOf(strap.first, phone.first)
        if (overlap <= 0) return false
        val length = strap.last - strap.first
        return length - overlap <= maxOf(SAME_WORKOUT_OUTSIDE_S, (length * SAME_WORKOUT_OUTSIDE_SHARE).toLong())
    }

    /** The share of a phone workout a strap record must cover for its figures to stand for the phone workout's. */
    const val STRAP_EFFECT_COVER = 0.9f

    /**
     * Whether the strap record over [strap] covers nearly all of the phone workout over [phone] ([STRAP_EFFECT_COVER]),
     * as a session it ran alongside does, so its own EPOC and training effect describe the whole workout. One the strap
     * only noticed partway through doesn't: its figures are for part of it.
     */
    fun strapCovers(strap: LongRange, phone: LongRange): Boolean {
        val length = phone.last - phone.first
        val overlap = minOf(strap.last, phone.last) - maxOf(strap.first, phone.first)
        return length > 0 && overlap >= length * STRAP_EFFECT_COVER && sameWorkout(strap, phone)
    }

    /**
     * The workout of [rated] the time left to recover counts from: the one whose own recovery runs out last (its end
     * plus [WorkoutAnalysis.ownRecoveryMinutes], else its whole time), so a short walk after a long one points at the
     * long one, whose hours it carries.
     */
    fun recoverySource(rated: List<WorkoutAnalysis>): WorkoutAnalysis? =
        rated.maxByOrNull { it.end + 60L * (it.ownRecoveryMinutes ?: it.recoveryMinutes ?: 0) }

    /** The phone workout of [phone] that [w] is the strap's record of ([sameWorkout]), if any. */
    fun phoneTwin(w: Workout, phone: List<org.openstrap.app.data.db.AppWorkout>, now: Long): org.openstrap.app.data.db.AppWorkout? =
        phone.firstOrNull { sameWorkout(w.start..w.end, phoneRange(it, now)) }

    /** Every strap record of [strap] that is the strap's own of the phone workout over [phone] ([sameWorkout]), by start. */
    fun strapTwins(strap: List<Workout>, phone: LongRange): List<Workout> =
        strap.filter { sameWorkout(it.start..it.end, phone) }.sortedBy { it.start }

    /**
     * The strap's workouts [ws] and the phone's [phone] as the workout lists show them, newest first: a strap workout's
     * end placed by its [last] HR sample or its effect, a phone strength workout with its count of finished [sets]
     * (by start). A strap workout that is the strap's record of a phone one ([sameWorkout]) is left out, so a walk
     * you started and the strap also noticed is one workout; its page still shows the strap's figures. Zones are
     * left empty: the lists fill them in apart.
     */
    fun listed(
        ws: List<Workout>, effects: List<SportEffectRow>, last: List<WorkoutHeartRate>, phone: List<org.openstrap.app.data.db.AppWorkout>,
        sets: Map<Long, Int>, maxHr: Int, now: Long = System.currentTimeMillis() / 1000,
    ): List<WorkoutAnalysis> {
        val lastByWorkout = last.associateBy { it.workoutStart }
        val strap = ws.filter { phoneTwin(it, phone, now) == null }.map { w -> analyse(w, listOfNotNull(lastByWorkout[w.start]), effects, maxHr) }
        return (strap + phone.map { WorkoutRecorder.analyse(it, emptyList(), maxHr).copy(sets = sets[it.start]) }).sortedByDescending { it.workout.start }
    }

    /** The sport effect shown with [w]: the first that ends after its start, within [EFFECT_WINDOW_S]. */
    fun effectOf(w: Workout, effects: List<SportEffectRow>): SportEffectRow? =
        effects.filter { it.end > w.start && it.end <= w.start + EFFECT_WINDOW_S }.minByOrNull { it.end }

    /** [w]'s real end: its sport effect's, else three minutes before its last HR sample ([lastSample]), else its stored end. */
    fun endOf(w: Workout, lastSample: Long?, effect: SportEffectRow?): Long = effect?.end
        ?: lastSample?.let { it - RECOVERY_TAIL_S }?.takeIf { it > w.start }?.let { maxOf(it, w.end) }
        ?: w.end

    /**
     * The sport effect deleted along with [w]: the one shown with it, unless another workout starts ([otherStarts])
     * before that effect ends. A strap workout with no effect of its own would otherwise take the next one's.
     */
    fun ownEffect(w: Workout, effects: List<SportEffectRow>, otherStarts: List<Long>): SportEffectRow? {
        val e = effectOf(w, effects) ?: return null
        return e.takeIf { otherStarts.none { s -> s > w.start && s < e.end } }
    }

    /** Zone 0..4 for 50–60, 60–70, 70–80, 80–90 and 90+ % of max; null below 50 %. */
    fun zoneOf(bpm: Int, maxHr: Int): Int? {
        val pct = bpm * 100 / maxHr
        return if (pct < 50) null else ((pct - 50) / 10).coerceAtMost(ZONES - 1)
    }

    /** HR drop from the end to [after] seconds later, each taken as a 5-second median to ride out noise. */
    private fun recovery(hr: List<WorkoutHeartRate>, end: Long, after: Long): Int? {
        val a = medianAt(hr, end) ?: return null
        val b = medianAt(hr, end + after) ?: return null
        return a - b
    }

    /**
     * The same drop from (time, bpm) [readings], as the summary right after a phone workout reads them: what it
     * recorded, then what the strap sent after the end.
     */
    fun heartRateDrop(readings: List<Pair<Long, Int>>, end: Long, after: Long = 60): Int? = recovery(asSamples(readings), end, after)

    /** Heart rate at [t] from (time, bpm) [readings], as a 5-second median; the starting point of [heartRateDrop]. */
    fun heartRateAt(readings: List<Pair<Long, Int>>, t: Long): Int? = medianAt(asSamples(readings), t)

    private fun asSamples(readings: List<Pair<Long, Int>>) = readings.map { (t, bpm) -> WorkoutHeartRate(0, t, bpm) }

    private fun medianAt(hr: List<WorkoutHeartRate>, t: Long): Int? =
        hr.filter { it.timestamp in t - 2..t + 2 }.map { it.bpm }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** Zepp's sport type ids (Type.type in the summary) for the sports a strap is likely to record. */
    fun sportName(type: Int): String = SPORT_NAMES[type] ?: tr(R.string.common_workout)

    /**
     * [a]'s active kcal, rounded: a phone workout's saved figure; a strap workout's kcal less [bmrPerMinute] for each
     * minute from its start to its stored end, as the day counts its minutes ([RecoveryEngine.energy]), never below
     * zero. Without a profile ([bmrPerMinute] null) the strap's own figure stands. Null when there's no kcal at all.
     */
    fun activeKcal(a: WorkoutAnalysis, bmrPerMinute: Float?): Int? {
        if (a.fromPhone) return (a.activeKcal ?: a.workout.calories?.toFloat())?.let(Math::round)
        val kcal = a.workout.calories ?: return null
        if (bmrPerMinute == null) return kcal
        val minutes = (a.workout.end - a.workout.start) / 60f
        return Math.round((kcal - bmrPerMinute * minutes).coerceAtLeast(0f))
    }

    /** Every sport a workout can be started as, by name, one id per name (Zepp has two "Free training"). */
    val sports: List<Pair<Int, String>> by lazy {
        SPORT_NAMES.entries.filter { it.key !in HIDDEN }.distinctBy { it.value }.map { it.key to it.value }.sortedBy { it.second }
    }

    /**
     * Sports kept for naming workouts already recorded (by the strap, Zepp or an import) but not offered for new ones:
     * each is another one under a second name, or the strap's own generic "Activity".
     */
    val HIDDEN = setOf(143, 40, 224, 55, 10, 57, 58, 51, 109, 24, 226, 16, 223)

    /**
     * A sport offered once with an outdoor and an indoor kind (spec 6.3): Ready's switch picks one, and the workout is
     * saved as that kind's own sport type, so the name says which it was ("Treadmill", "Indoor walk") wherever it
     * shows. [outdoor] is the sport's key, the type its tile starts. [indoorByDefault] is the side
     * shown before you've picked one.
     */
    data class Sides(val outdoor: Int, val indoor: Int, val outdoorName: String, val indoorName: String, val indoorByDefault: Boolean = false) {
        /** The sport type for the [indoor] side. */
        fun type(indoor: Boolean): Int = if (indoor) this.indoor else outdoor
    }

    val SIDES = listOf(
        Sides(1, 2, tr(R.string.workouts_outdoor), tr(R.string.workouts_treadmill)),
        Sides(4, 8, tr(R.string.workouts_outdoor), tr(R.string.workouts_indoor)),
        // Most swims are in a pool.
        Sides(7, 6, tr(R.string.workouts_open_water), tr(R.string.workouts_pool), indoorByDefault = true),
        Sides(3, 40, tr(R.string.workouts_outdoor), tr(R.string.workouts_indoor)),
    )

    /** The [Sides] [type] is one of, or null for a sport with one kind. */
    fun sidesOf(type: Int): Sides? = SIDES.firstOrNull { type == it.outdoor || type == it.indoor }

    /**
     * Which side Ready opens on for [type]: the indoor kind when [type] is one (Treadmill picked by name), else the
     * side you last chose for the sport ([remembered], by its key), else its default. Null for a sport with one kind.
     */
    fun indoorSide(type: Int, remembered: Map<Int, Boolean>): Boolean? {
        val s = sidesOf(type) ?: return null
        if (type == s.indoor && s.indoor != s.outdoor) return true
        return remembered[s.outdoor] ?: s.indoorByDefault
    }

    /** Whether a workout of [type] on the [indoor] side gets GPS: the outdoor side, or a sport done outdoors. */
    fun gpsOffered(type: Int, indoor: Boolean?): Boolean = if (sidesOf(type) != null) indoor == false else type in OUTDOOR

    /** The sides you chose, stored as "1=0,7=1" (key, 1 for indoor), and back; anything unreadable is left out. */
    fun formatSides(sides: Map<Int, Boolean>): String = sides.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${if (it.value) 1 else 0}" }

    fun parseSides(text: String?): Map<Int, Boolean> = text.orEmpty().split(',').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val key = k.trim().toIntOrNull()?.takeIf { sidesOf(it)?.outdoor == it } ?: return@mapNotNull null
        when (v.trim()) { "1" -> key to true; "0" -> key to false; else -> null }
    }.toMap()

    /**
     * The picker's twelve everyday sports (spec 6.3), by the type each tile starts: Run, Walk, Hike, Cycling, Swim,
     * Strength, HIIT, Yoga, Pilates, Rowing, Elliptical, Other. Every other sport is behind More sports.
     */
    val EVERYDAY = listOf(1, 3, 15, 4, 7, 52, 49, 60, 61, 23, 9, 5)

    /** The tiles' short names; a sport with two kinds has one name for both. */
    private val SHORT_NAMES = mapOf(
        1 to tr(R.string.common_run), 2 to tr(R.string.common_run), 3 to tr(R.string.common_walk), 40 to tr(R.string.common_walk), 15 to tr(R.string.workouts_hike), 4 to tr(R.string.common_cycling), 8 to tr(R.string.common_cycling), 7 to tr(R.string.workouts_swim), 6 to tr(R.string.workouts_swim),
        52 to tr(R.string.common_strength), 49 to tr(R.string.workouts_hiit), 60 to tr(R.string.workouts_yoga), 61 to tr(R.string.workouts_pilates), 23 to tr(R.string.common_rowing), 9 to tr(R.string.workouts_elliptical), 5 to tr(R.string.workouts_other),
    )

    /** "Run" for an outdoor run or a treadmill: the picker's name, else the sport's full name. */
    fun shortName(type: Int): String = SHORT_NAMES[type] ?: sportName(type)

    /** The type a sport's tile stands for: a sport with two kinds by its key, any other as itself. */
    fun tileType(type: Int): Int = sidesOf(type)?.outdoor ?: type

    /** More sports: every sport offered that isn't an everyday tile or behind one's switch, A to Z. */
    val MORE: List<Int> by lazy {
        val behindTiles = EVERYDAY.toSet() + SIDES.flatMap { listOf(it.outdoor, it.indoor) }
        sports.map { it.first }.filter { it !in behindTiles }
    }

    /**
     * What a search for [query] finds, as tiles: everyday sports by their short name, then every other sport, both
     * kinds of a two-kind sport included ("Treadmill"), by its full name. Each once; empty for a blank query.
     */
    fun search(query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val everyday = EVERYDAY.filter { shortName(it).contains(q, ignoreCase = true) || sportName(it).contains(q, ignoreCase = true) }
        val others = sports.map { it.first }.filter { it !in everyday && sportName(it).contains(q, ignoreCase = true) }
        return everyday + others
    }

    /**
     * The picker's Recent row: your last [n] sports, [types] newest first, each by its tile ([tileType]), without the
     * strap's own "Activity".
     */
    fun recentSports(types: List<Int>, n: Int = 4): List<Int> =
        types.filter { it != GENERIC_ACTIVITY && it in SPORT_NAMES }.map(::tileType).distinct().take(n)

    /** Sports done outdoors, where the ready screen offers GPS. */
    val OUTDOOR = setOf(1, 3, 4, 11, 12, 15, 215, 224, 225)

    /** Sports done only outdoors, whose tile carries the location mark: no indoor side to choose. */
    val OUTDOOR_ONLY: Set<Int> get() = OUTDOOR - SIDES.map { it.outdoor }.toSet()

    /**
     * Sports done on your feet, where steps times a stride make a distance (≈) without GPS: runs, walks, hikes. On a
     * bike, in water or on a mat, steps say nothing about distance, so those show none.
     */
    val ON_FOOT = setOf(1, 2, 3, 11, 15, 40, 215, 224, 225)

    /** Runs, where distance comes with a pace. */
    val RUNS = setOf(1, 2, 11)

    /** On foot but not running: walks and hikes, where distance alone says it. */
    val WALKS = ON_FOOT - RUNS

    /** A workout's effort glow never goes below this, so a light one, or one with no Load, still shows a trace. */
    const val EFFORT_MIN = 0.12f
    /** Workouts with a Load before your usual counts: fewer and the fixed [EFFORT_FULL_LOAD] scale is used. */
    const val USUAL_LOAD_MIN = 3
    /** How many recent workouts your usual is taken from. */
    const val USUAL_LOAD_OF = 20
    /** Without a usual, the Load (EPOC) that lights a workout fully: a hard session's. */
    const val EFFORT_FULL_LOAD = 150f

    /**
     * Your usual workout Load: the median [Workout.trainingLoad] (EPOC, the strap's or OpenStrap's on the same scale)
     * of the latest [USUAL_LOAD_OF] workouts with one, [loads] newest first. Null with fewer than [USUAL_LOAD_MIN].
     * Not the day's Load: that is TRIMP over every minute, on another scale.
     */
    fun usualLoad(loads: List<Int?>): Float? {
        val recent = loads.filterNotNull().filter { it > 0 }.take(USUAL_LOAD_OF).sorted()
        if (recent.size < USUAL_LOAD_MIN) return null
        val mid = recent.size / 2
        return if (recent.size % 2 == 1) recent[mid].toFloat() else (recent[mid - 1] + recent[mid]) / 2f
    }

    /**
     * How strongly a workout's effort light glows, 0..1, from its [load]: a usual workout at half, twice your
     * [usual] or more at full. Without a usual, [EFFORT_FULL_LOAD] is full. No Load glows at [EFFORT_MIN].
     */
    fun effort(load: Int?, usual: Float?): Float {
        if (load == null || load <= 0) return EFFORT_MIN
        val full = usual?.takeIf { it > 0f }?.let { it * 2f } ?: EFFORT_FULL_LOAD
        return (load / full).coerceIn(EFFORT_MIN, 1f)
    }

    /** Offered before any workout has been recorded: outdoor run, walk, strength training. */
    val DEFAULT_SPORTS = listOf(1, 3, 52)

    /** "Activity": what the strap files movement it logged by itself under; a record, not a sport anyone starts. */
    const val GENERIC_ACTIVITY = 223

    private val SPORT_NAMES = mapOf(
        1 to tr(R.string.workouts_outdoor_run), 2 to tr(R.string.workouts_treadmill), 3 to tr(R.string.common_walk), 4 to tr(R.string.workouts_outdoor_cycling), 5 to tr(R.string.workouts_free_training),
        6 to tr(R.string.workouts_pool_swim), 7 to tr(R.string.workouts_open_water_swim), 8 to tr(R.string.workouts_indoor_cycling), 9 to tr(R.string.workouts_elliptical), 10 to tr(R.string.workouts_climbing),
        11 to tr(R.string.workouts_trail_run), 12 to tr(R.string.workouts_skiing), 15 to tr(R.string.common_hiking), 16 to tr(R.string.workouts_free_training), 17 to tr(R.string.workouts_tennis), 18 to tr(R.string.workouts_soccer),
        21 to tr(R.string.workouts_jump_rope), 23 to tr(R.string.workouts_rowing_machine), 24 to tr(R.string.workouts_indoor_fitness), 40 to tr(R.string.workouts_indoor_walk), 49 to tr(R.string.workouts_hiit),
        50 to tr(R.string.workouts_core_training), 51 to tr(R.string.workouts_mixed_aerobic), 52 to tr(R.string.common_strength_training), 53 to tr(R.string.workouts_stretching),
        55 to tr(R.string.workouts_flexibility), 57 to tr(R.string.workouts_step_training), 58 to tr(R.string.workouts_stair_stepper), 60 to tr(R.string.workouts_yoga), 61 to tr(R.string.workouts_pilates),
        70 to tr(R.string.workouts_rock_climbing), 76 to tr(R.string.workouts_dance), 83 to tr(R.string.workouts_golf), 85 to tr(R.string.workouts_basketball), 88 to tr(R.string.workouts_volleyball),
        89 to tr(R.string.workouts_table_tennis), 92 to tr(R.string.workouts_badminton), 97 to tr(R.string.workouts_boxing), 108 to tr(R.string.workouts_stair_climbing), 109 to tr(R.string.workouts_aerobics),
        130 to tr(R.string.workouts_cross_training), 143 to tr(R.string.workouts_spinning), 204 to tr(R.string.workouts_pickleball), 205 to tr(R.string.workouts_padel), 215 to tr(R.string.workouts_dog_walking),
        222 to tr(R.string.workouts_hyrox_training), 223 to tr(R.string.common_activity), 224 to tr(R.string.workouts_mountain_hike), 225 to tr(R.string.workouts_rucking), 226 to tr(R.string.workouts_hybrid_training),
    )
}
