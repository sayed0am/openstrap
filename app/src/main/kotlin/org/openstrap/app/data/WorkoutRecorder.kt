package org.openstrap.app.data

import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.data.settings.UserProfile

/**
 * A workout recorded on the phone, worked out in pure Kotlin like [Recovery]: which minutes the phone missed, how the
 * strap's history fills them, and the figures shown afterwards. The phone records live heart rate about once a second;
 * after the next sync the strap's per-minute history fills every whole minute with no live sample, so a disconnect or a
 * killed app leaves a coarser stretch, never a hole.
 */
object WorkoutRecorder {
    /**
     * The zone shown live, 0..4: below zone 2 is zone 1 (anything slower is easy), then your zones, as
     * [Workouts.zoneOf]. Null for no reading.
     */
    fun liveZone(bpm: Int?, maxHr: Int): Int? = bpm?.let { Workouts.zoneOf(it, maxHr) ?: 0 }

    /** Where [bpm] sits along the five zones ([HeartZones.position]), 0..1: zone 1's start at the left, each zone a fifth. */
    fun zonePosition(bpm: Int, maxHr: Int): Float = HeartZones.current.value.position(bpm, maxHr)

    /** Seconds in each live zone over [samples] (time, bpm, ascending); a gap counts up to [MAX_STEP_S]. */
    fun liveZoneSeconds(samples: List<Pair<Long, Int>>, maxHr: Int): IntArray {
        val out = IntArray(Workouts.ZONES)
        samples.zipWithNext().forEach { (a, b) -> out[liveZone(a.second, maxHr)!!] += (b.first - a.first).coerceIn(0, MAX_STEP_S).toInt() }
        return out
    }

    const val MAX_STEP_S = 5L

    /** Live readings outside this range are the sensor settling or off the skin, not a heart rate. */
    fun isValid(bpm: Int) = bpm in 30..240

    /** A history minute is drawn as samples this far apart, so charts and zone time treat it like live data. */
    private const val HISTORY_STEP_S = 5L

    /**
     * Minute starts (epoch seconds, on the minute) wholly inside [start, end) and outside every pause, with no live
     * sample in them: the minutes gap filling looks up. Minutes cut by the start, the end or a pause are left alone.
     */
    fun missingMinutes(start: Long, end: Long, pauses: List<LongRange>, live: Collection<Long>): List<Long> {
        val covered = live.mapTo(HashSet()) { Math.floorDiv(it, 60L) * 60 }
        val out = mutableListOf<Long>()
        var m = Math.floorDiv(start + 59, 60L) * 60
        while (m + 60 <= end) {
            if (m !in covered && pauses.none { it.first < m + 60 && it.last > m }) out += m
            m += 60
        }
        return out
    }

    /**
     * Rows for [missing] minutes from the strap's history: its per-second heart rate averaged for the minute
     * ([secondAverages], the heart_rate table) when it has one, else the minute's own record ([minuteRecords],
     * activity_minute). Both keyed by minute start. Minutes the strap has nothing for stay missing.
     */
    fun fillFromHistory(
        workoutStart: Long,
        missing: List<Long>,
        secondAverages: Map<Long, Int>,
        minuteRecords: Map<Long, Int>,
    ): List<AppWorkoutHeartRate> = missing.mapNotNull { m ->
        val bpm = (secondAverages[m] ?: minuteRecords[m])?.takeIf(::isValid) ?: return@mapNotNull null
        AppWorkoutHeartRate(workoutStart, m, bpm, fromHistory = true)
    }

    /** Each history minute spread over [HISTORY_STEP_S] samples across its minute; live samples as they are. */
    fun expand(hr: List<AppWorkoutHeartRate>): List<WorkoutHeartRate> = hr.flatMap { s ->
        if (!s.fromHistory) listOf(WorkoutHeartRate(s.workoutStart, s.timestamp, s.bpm))
        else (0 until 60 step HISTORY_STEP_S.toInt()).map { WorkoutHeartRate(s.workoutStart, s.timestamp + it, s.bpm) }
    }.sortedBy { it.timestamp }

    data class Stats(val average: Int, val max: Int, val min: Int)

    /** Time-weighted: a live sample stands for a second, a history row for its minute. Null without samples. */
    fun stats(hr: List<AppWorkoutHeartRate>): Stats? {
        if (hr.isEmpty()) return null
        var sum = 0L
        var weight = 0L
        hr.forEach { s ->
            val w = if (s.fromHistory) 60L else 1L
            sum += s.bpm * w
            weight += w
        }
        return Stats(Math.round(sum.toDouble() / weight).toInt(), hr.maxOf { it.bpm }, hr.minOf { it.bpm })
    }

    /** Seconds actually moving: the shown start to the end (or [now]) less every pause. */
    fun activeSeconds(w: AppWorkout, now: Long): Long = ((w.end ?: now) - w.from - w.pausedSeconds(w.end ?: now)).coerceAtLeast(0)

    /** The phone workout in the strap workout's shape, so lists and the detail screen show both alike. */
    fun toWorkout(w: AppWorkout, now: Long, stats: Stats? = null) = Workout(
        start = w.start,
        end = w.end ?: now,
        sportType = w.sportType,
        exerciseSeconds = activeSeconds(w, now).toInt(),
        calories = w.totalKcal?.let { Math.round(it) },
        // The strap's own record of the workout ([withStrapRecord]) stands over the phone's live readings.
        averageHeartRate = (if (w.effectFromStrap) w.averageHeartRate else null) ?: stats?.average ?: w.averageHeartRate,
        maxHeartRate = (if (w.effectFromStrap) w.maxHeartRate else null) ?: stats?.max ?: w.maxHeartRate,
        minHeartRate = stats?.min,
        aerobicTrainingEffect = w.aerobicTrainingEffect,
        // Only the strap's: no open model holds up for it from heart rate alone ([WorkoutEffect]).
        anaerobicTrainingEffect = w.anaerobicTrainingEffect,
        // The strap's load is Zepp's int EPOC (native 04 §2.1); ours is on the same scale, so the two compare.
        trainingLoad = w.epoc?.toInt(),
        vo2max = w.vo2max?.let { Math.round(it) },
        steps = null,
    )

    /**
     * A phone workout analysed like a strap one, over its shown range only (a trim or an early end leaves the rest
     * recorded but out of the figures); [hr] empty gives a list row.
     */
    fun analyse(w: AppWorkout, hr: List<AppWorkoutHeartRate>, maxHeartRate: Int, now: Long = System.currentTimeMillis() / 1000): WorkoutAnalysis {
        val end = w.end ?: now
        val shown = shown(w, hr, end)
        return Workouts.analyse(toWorkout(w, now, stats(shown)), expand(shown).filter { it.timestamp in w.from..end }, emptyList(), maxHeartRate)
            .copy(
                fromPhone = true, historyMinutes = shown.count { it.fromHistory }, historyRanges = historyRanges(shown),
                shownStart = w.from, recordedEnd = w.recordedEnd, recoveryMinutes = w.recoveryMinutes, ownRecoveryMinutes = w.ownRecoveryMinutes,
                distanceMeters = w.distanceMeters, distanceFromSteps = w.distanceFromSteps, activeKcal = w.activeKcal,
                target = w.target, targetReachedSeconds = reachedSeconds(w, end),
            )
    }

    /**
     * Active seconds into [w] when its kcal target was reached: from its shown start, less the pauses before. Null when
     * it wasn't, or when a trim left that moment outside the shown range up to [end].
     */
    fun reachedSeconds(w: AppWorkout, end: Long = w.end ?: Long.MAX_VALUE): Long? =
        w.targetReachedAt?.takeIf { it in w.from..end }?.let { (it - w.from - w.pausedSeconds(it)).coerceAtLeast(0) }

    /** The samples inside the shown range; a history row stands for its whole minute, so it counts when the minute overlaps. */
    fun shown(w: AppWorkout, hr: List<AppWorkoutHeartRate>, end: Long = w.end ?: Long.MAX_VALUE): List<AppWorkoutHeartRate> =
        hr.filter { it.timestamp in (if (it.fromHistory) w.from - 59 else w.from)..end }

    /** [w] with its stored average and peak worked out again over its shown range, for the lists. */
    fun withStats(w: AppWorkout, hr: List<AppWorkoutHeartRate>): AppWorkout =
        stats(shown(w, hr)).let { w.copy(averageHeartRate = it?.average, maxHeartRate = it?.max) }

    /**
     * [w] with the figures of [s], the strap's own record of nearly all of it ([Workouts.strapCovers]), and [effect], its
     * sport effect: its EPOC as Load, both training effects, its recovery time, and its average and peak heart rate,
     * over every second it measured, where the live stream can miss stretches (on the strap, 255 seconds against 205
     * live, the shared ones within 2 bpm). Its VO2max stays the phone's. Null without an EPOC, so the model's figures stand.
     */
    fun withStrapRecord(w: AppWorkout, s: Workout, effect: org.openstrap.app.data.db.SportEffectRow?): AppWorkout? {
        val epoc = effect?.epoc ?: s.trainingLoad?.toFloat() ?: return null
        return w.copy(
            epoc = epoc, aerobicTrainingEffect = s.aerobicTrainingEffect, anaerobicTrainingEffect = s.anaerobicTrainingEffect,
            recoveryMinutes = effect?.recoveryMinutes ?: w.recoveryMinutes, effectFromStrap = true, ownRecoveryMinutes = null,
            averageHeartRate = s.averageHeartRate ?: w.averageHeartRate, maxHeartRate = s.maxHeartRate ?: w.maxHeartRate,
        )
    }

    /* ------------------------------------ The strap's own session (0x0019) ----------------------------------- */

    /** What a recording workout does when the strap changes its session itself. */
    enum class StrapAction { PAUSE, RESUME, END }

    /**
     * The answer to the strap changing its session's state itself, as Zepp's `uv60.i()` follows it: a pause or resume
     * only when it changes something, a stop ends the workout. Zepp leaves a start or an abandon alone, and so does this.
     */
    fun strapAction(state: org.openstrap.protocol.services.SportSession.State, paused: Boolean): StrapAction? = when (state) {
        org.openstrap.protocol.services.SportSession.State.PAUSE -> StrapAction.PAUSE.takeIf { !paused }
        org.openstrap.protocol.services.SportSession.State.RESUME -> StrapAction.RESUME.takeIf { paused }
        org.openstrap.protocol.services.SportSession.State.STOP -> StrapAction.END
        else -> null
    }

    /** The state that closes the strap's session: STOP for a workout that is kept, ABANDON for one that isn't, so the strap drops its record. */
    fun closingState(kept: Boolean) =
        if (kept) org.openstrap.protocol.services.SportSession.State.STOP else org.openstrap.protocol.services.SportSession.State.ABANDON

    /* ---------------------------------------------- Energy --------------------------------------------- */

    /** A workout's kcal: above rest (what the workout shows) and in all. */
    data class Kcal(val active: Float, val total: Float)

    /**
     * kcal for each active minute of [w] ([EnergyExpenditure.workoutMinutes]): the minute starts inside its shown range
     * less its pauses (the one under way included), up to [now] while it records, with the heart rate of [hr] in them,
     * live and history alike. [steps] is steps per minute start. Null without weight, height and age.
     */
    fun kcalMinutes(
        w: AppWorkout, hr: List<AppWorkoutHeartRate>, steps: Map<Long, Int>, restingHr: Float, maxHr: Float,
        profile: UserProfile, age: Int?, vo2max: Float?, now: Long = System.currentTimeMillis() / 1000,
    ): List<EnergyExpenditure.WorkoutMinute>? {
        val end = w.end ?: now
        val ranges = Recovery.activeRanges(w, end).filter { !it.isEmpty() }
        val minutes = ranges.flatMap { r -> (Math.floorDiv(r.first + 59, 60L) * 60..r.last step 60).toList() }.distinct()
        val hrr = expand(shown(w, hr, end)).filter { s -> isValid(s.bpm) && ranges.any { s.timestamp in it } }
            .map { it.timestamp to Recovery.hrr(it.bpm, restingHr, maxHr) }
        return EnergyExpenditure.workoutMinutes(minutes, hrr, steps, profile, age, vo2max)
    }

    /** The sum of [minutes]. */
    fun kcal(minutes: List<EnergyExpenditure.WorkoutMinute>): Kcal =
        Kcal(minutes.sumOf { it.activeKcal.toDouble() }.toFloat(), minutes.sumOf { it.kcal.toDouble() }.toFloat())

    /** [w] with its kcal saved from [minutes]; null [minutes] (no profile) leaves it without. */
    fun withKcal(w: AppWorkout, minutes: List<EnergyExpenditure.WorkoutMinute>?): AppWorkout =
        minutes?.let { kcal(it) }.let { w.copy(activeKcal = it?.active, totalKcal = it?.total) }

    /**
     * The running kcal while [w] records, from [minutes] ([kcalMinutes] with [now]): every minute that has ended, and
     * the one under way for the share of it gone. Never below [previous], so the figure only climbs, even when a
     * minute's mean heart rate drops before it ends or a history fill reworks one. With [active] (the workout's active
     * ranges), a minute counts only for its seconds inside them, so a pause holds the figure instead of letting the
     * minute it began in fill up with the clock.
     */
    fun liveKcal(minutes: List<EnergyExpenditure.WorkoutMinute>, now: Long, previous: Kcal? = null, active: List<LongRange>? = null): Kcal {
        var activeKcal = 0.0
        var total = 0.0
        for (m in minutes) {
            if (m.t > now) break
            val hi = minOf(m.t + 60, now)
            val seconds = active?.sumOf { r -> (minOf(hi, r.last + 1) - maxOf(m.t, r.first)).coerceAtLeast(0) } ?: (hi - m.t)
            val share = (seconds / 60.0).coerceAtMost(1.0)
            activeKcal += m.activeKcal * share
            total += m.kcal * share
        }
        return Kcal(maxOf(activeKcal.toFloat(), previous?.active ?: 0f), maxOf(total.toFloat(), previous?.total ?: 0f))
    }

    /**
     * Steps per minute start from the strap's running step totals ([totals]: time, total, oldest first), which come
     * about once a minute while it is connected: each rise is spread over the seconds between its two totals. A total
     * that falls (the day rolled over, the strap restarted) or comes more than [MAX_STEP_SPREAD_S] after the one before
     * gives nothing; those minutes take what a sync brings. Each minute is held to 0..250, as the strap's own minutes.
     */
    fun minuteSteps(totals: List<Pair<Long, Int>>): Map<Long, Int> {
        val out = HashMap<Long, Double>()
        for (i in 1 until totals.size) {
            val (t0, s0) = totals[i - 1]
            val (t1, s1) = totals[i]
            val span = t1 - t0
            if (span <= 0 || span > MAX_STEP_SPREAD_S || s1 < s0) continue
            var m = Math.floorDiv(t0, 60L) * 60
            while (m < t1) {
                val overlap = minOf(m + 60, t1) - maxOf(m, t0)
                if (overlap > 0) out[m] = (out[m] ?: 0.0) + (s1 - s0) * overlap.toDouble() / span
                m += 60
            }
        }
        return out.mapValues { Math.round(it.value).toInt().coerceIn(0, 250) }
    }

    /**
     * One tick of the live kcal ([liveKcal]) for [w] as it records at [now]: its [live] readings (time, bpm), the
     * minutes a history fill wrote ([history]), steps from the strap's live totals ([stepTotals], [minuteSteps]) with
     * the strap's own minutes ([syncedSteps]) taking over once a sync brings them. Never below [previous]; null
     * without weight, height and age.
     */
    fun liveKcalTick(
        w: AppWorkout, live: List<Pair<Long, Int>>, history: List<AppWorkoutHeartRate>, stepTotals: List<Pair<Long, Int>>,
        syncedSteps: Map<Long, Int>, restingHr: Float, maxHr: Float, profile: UserProfile, age: Int?, vo2max: Float?,
        now: Long, previous: Kcal?,
    ): Kcal? {
        val hr = (history.filter { it.fromHistory } + live.map { AppWorkoutHeartRate(w.start, it.first, it.second) }).sortedBy { it.timestamp }
        val minutes = kcalMinutes(w, hr, minuteSteps(stepTotals) + syncedSteps, restingHr, maxHr, profile, age, vo2max, now) ?: return previous
        return liveKcal(minutes, now, previous, Recovery.activeRanges(w, now))
    }

    /** Longer than this between two step totals, the strap was away: its steps aren't spread over the gap. */
    const val MAX_STEP_SPREAD_S = 15 * 60L

    /** One lap: its number, when it began and ended, its average heart rate, and metres when there was GPS. */
    data class Lap(val index: Int, val start: Long, val end: Long, val averageHeartRate: Int?, val meters: Double?)

    /**
     * The laps of a workout from [from] to [end] split at [marks]: the last one runs to the end. Heart rate is
     * (time, bpm); [route] gives each lap's distance when the workout had GPS. Pauses count against no lap.
     */
    fun laps(from: Long, end: Long, marks: List<Long>, hr: List<Pair<Long, Int>>, route: List<Route.Point>, pauses: List<LongRange>): List<Lap> {
        val bounds = listOf(from) + marks.filter { it in (from + 1) until end }.sorted() + end
        return bounds.zipWithNext().mapIndexed { i, (a, b) ->
            val bpm = hr.filter { it.first in a until b }.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }
            val meters = route.filter { it.t in a..b }.takeIf { it.size >= 2 }?.let { Route.distance(it, pauses) }
            Lap(i, a, b, bpm, meters)
        }
    }

    /* ------------------------------------------- Ending well ------------------------------------------- */

    /** Heart rate counts as "near resting" up to this share of the reserve above resting. */
    const val CALM_HRR = 0.25f
    /** Steps between two of the strap's totals that count as moving. */
    const val MOVING_STEPS = 20
    /** How long calm and still before asking whether the workout is over (spec: about 8 minutes). */
    const val STOPPED_S = 8 * 60L

    /**
     * When the wearer last moved, if they have been calm and still for [STOPPED_S] since: the end a "Looks like
     * you've stopped" prompt proposes. Active is a heart rate above [CALM_HRR] of the reserve, or [MOVING_STEPS]
     * between two step totals ([steps]: time, the strap's running total, ascending). Null while still active, when
     * nothing active has happened since [since] (a calm session, or one already answered "Keep going"), or when
     * heart rate covers less than half of the calm stretch, so a dropped strap never ends a workout.
     */
    fun stoppedSince(hr: List<Pair<Long, Int>>, steps: List<Pair<Long, Int>>, restingHr: Int, maxHr: Int, since: Long, now: Long): Long? {
        val lastActive = lastActive(hr, steps, restingHr, maxHr) ?: return null
        if (lastActive <= since || now - lastActive < STOPPED_S) return null
        val covered = hr.count { it.first > lastActive }.toLong()
        return if (covered * 2 >= now - lastActive) lastActive else null
    }

    /** The last moment of a heart rate above [CALM_HRR] of the reserve, or of [MOVING_STEPS] between two step totals. */
    fun lastActive(hr: List<Pair<Long, Int>>, steps: List<Pair<Long, Int>>, restingHr: Int, maxHr: Int): Long? {
        val calm = restingHr + CALM_HRR * (maxHr - restingHr)
        val lastHr = hr.lastOrNull { it.second > calm }?.first
        val lastSteps = steps.zipWithNext().lastOrNull { (a, b) -> b.second - a.second >= MOVING_STEPS }?.second?.first
        return listOfNotNull(lastHr, lastSteps).maxOrNull()
    }

    /** What the summary compares a workout with: the averages of the [count] most recent similar ones. */
    data class Usual(val count: Int, val activeSeconds: Long, val averageHeartRate: Int?, val maxHeartRate: Int?, val zoneShares: FloatArray)

    /** How many similar workouts make "your usual", and how far a workout's length may differ to be similar. */
    const val USUAL_COUNT = 5
    private const val SIMILAR_LENGTH = 2.0

    /**
     * The workouts [a] is compared with: the same sport, ended before it, within [SIMILAR_LENGTH]× of its length
     * either way, newest first, at most [USUAL_COUNT].
     */
    fun similar(a: WorkoutAnalysis, others: List<WorkoutAnalysis>): List<WorkoutAnalysis> {
        val len = a.durationSeconds.coerceAtLeast(60).toDouble()
        return others.filter {
            // Starting well before it: the strap's own record of this same workout isn't "usual".
            it.workout.start != a.workout.start && it.workout.sportType == a.workout.sportType && it.workout.start < a.shownStart - 120 &&
                it.durationSeconds.coerceAtLeast(60) in (len / SIMILAR_LENGTH).toLong()..(len * SIMILAR_LENGTH).toLong()
        }.sortedByDescending { it.workout.start }.take(USUAL_COUNT)
    }

    /** "Your usual" from [similar] workouts analysed with their heart rate; null without any. */
    fun usual(similar: List<WorkoutAnalysis>): Usual? {
        if (similar.isEmpty()) return null
        val shares = FloatArray(Workouts.ZONES)
        similar.forEach { a ->
            val z = liveZoneSeconds(a.heartRate.filter { it.timestamp in a.shownStart..a.end }.map { it.timestamp to it.bpm }, a.maxHeartRate)
            val total = z.sum()
            if (total > 0) for (i in z.indices) shares[i] += z[i].toFloat() / total / similar.size
        }
        fun avg(f: (WorkoutAnalysis) -> Int?) = similar.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }
        return Usual(
            similar.size,
            similar.map { (it.workout.exerciseSeconds ?: it.durationSeconds.toInt()).toLong() }.average().toLong(),
            avg { it.workout.averageHeartRate }, avg { it.workout.maxHeartRate }, shares,
        )
    }

    /** Consecutive history minutes joined into runs, for marking them on a chart. */
    fun historyRanges(hr: List<AppWorkoutHeartRate>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        hr.filter { it.fromHistory }.map { it.timestamp }.sorted().forEach { m ->
            val last = out.lastOrNull()
            if (last != null && last.last + 1 == m) out[out.lastIndex] = last.first..(m + 59) else out += m..(m + 59)
        }
        return out
    }
}
