package org.openstrap.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.restingHrIn
import org.openstrap.app.data.settings.UserProfile
import java.time.Instant
import java.time.ZoneId

/**
 * Loads what [WorkoutEffect] needs for one phone workout and runs it, as [RecoveryEngine] does for [Recovery]: resting
 * and max HR by the app's own rules, the earlier workouts' EPOC (the phone's and the strap's, each workout once) for the
 * activity class, fitness and fatigue, and recovery time still owed.
 */
class WorkoutEffectEngine(private val dao: HealthDao) {

    /**
     * [w] with its effect figures from [hr] over its shown range. [route] is the route in memory at the end; otherwise
     * a kept route is read back, and without either a VO2max worked out at the end is kept as it was.
     */
    suspend fun withEffect(
        w: AppWorkout, hr: List<AppWorkoutHeartRate>, profile: UserProfile, route: List<Route.Point>? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AppWorkout {
        val end = w.end ?: return w
        val from = w.from
        val engine = RecoveryEngine(dao)
        val date = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
        val maxHr = engine.maxHeartRate(profile, date, zone)
        val restingHr = restingHr(from, zone)

        val loads = earlierLoads(w, zone)
        val load28 = loads.filter { it.first >= from - 28 * DAY }.sumOf { it.second.toDouble() }.toFloat()
        val today = dayStart(from, zone)
        val daily = (TREND_DAYS - 1 downTo 0).map { back ->
            val d = today - back * DAY
            loads.filter { dayStart(it.first, zone) == d }.sumOf { it.second.toDouble() }.toFloat()
        }
        val trend = Recovery.fitnessFatigue(daily).last()

        val points = route ?: if (w.keepRoute) dao.routeOnce(w.start).map { it.point } else null
        val shownHr = WorkoutRecorder.expand(WorkoutRecorder.shown(w, hr, end)).map { it.timestamp to it.bpm }
        val r = WorkoutEffect.compute(
            w.sportType, shownHr, from, end, w.pauseRanges, points?.filter { it.t in from..end }.orEmpty(),
            restingHr, maxHr, load28, trend, carriedMinutes(w),
        )
        // kcal from scratch over the range as it now stands, so the saved total isn't fitted to the old one.
        val kcal = catching { engine.phoneWorkoutKcal(w.copy(totalKcal = null), profile, profile.age(date), restingHr, maxHr, engine.vo2max(profile, date, zone), end, hr) }
            .onFailure { android.util.Log.w(TAG, "workout effect: kcal for ${w.start} not worked out: $it") }
            .getOrNull()
        val modelled = WorkoutRecorder.withKcal(w, kcal).copy(
            epoc = r.epoc, aerobicTrainingEffect = r.aerobicTe, recoveryMinutes = r.recoveryMinutes,
            vo2max = if (points == null) w.vo2max else r.vo2max, anaerobicTrainingEffect = null, effectFromStrap = false,
            ownRecoveryMinutes = r.ownRecoveryMinutes,
        )
        return strapEffect(modelled) ?: modelled
    }

    /**
     * [w] with the strap's own figures ([WorkoutRecorder.withStrapRecord]), when the strap kept a record of nearly all
     * of it ([Workouts.strapCovers]), as it does of a session it ran alongside; they come from Zepp's own models on the
     * strap. Null when it kept no such record, so the model's figures stand. The record only arrives with a sync after
     * the workout, hence [takeStrapEffects].
     */
    suspend fun strapEffect(w: AppWorkout): AppWorkout? {
        val end = w.end ?: return null
        val s = dao.workoutsOnce(w.from, end).firstOrNull { Workouts.strapCovers(it.start..it.end, w.from..end) } ?: return null
        return WorkoutRecorder.withStrapRecord(w, s, Workouts.effectOf(s, dao.sportEffectsOnce(s.start, s.start + Workouts.EFFECT_WINDOW_S)))
    }

    /**
     * After a sync: each phone workout that ended since [since] takes the strap's figures when the strap's record of it
     * has come in ([strapEffect]). Only the effect and heart rate columns are written, and only when they change.
     *
     * Each workout under [lock], the app's workout lock (`WorkoutSession.LOCK`), read again inside it, so a trim, an
     * end or a discard made since the list was read stands. Taken a workout at a time, never across the whole list, and
     * never by a caller that holds it already.
     */
    suspend fun takeStrapEffects(since: Long, lock: Mutex) {
        for (listed in dao.appWorkoutsOnce(since, Long.MAX_VALUE)) {
            if (listed.end == null) continue
            lock.withLock {
                val w = dao.appWorkoutOnce(listed.start)?.takeIf { it.end != null } ?: return@withLock
                val s = strapEffect(w)?.takeIf { it != w } ?: return@withLock
                dao.updateAppWorkoutEffect(
                    w.start, s.epoc, s.aerobicTrainingEffect, s.recoveryMinutes, s.vo2max, s.anaerobicTrainingEffect, fromStrap = true,
                    ownRecoveryMinutes = null,
                )
                dao.updateAppWorkoutHeartRateStats(w.start, s.averageHeartRate, s.maxHeartRate)
            }
        }
    }

    /**
     * Works out and saves the kcal of a finished workout that has none yet (one from before version 20), or [again]
     * one that has, after the profile changed, without touching its effect. Nothing is written without a profile.
     */
    suspend fun backfillKcal(w: AppWorkout, profile: UserProfile, zone: ZoneId = ZoneId.systemDefault(), again: Boolean = false) {
        val end = w.end ?: return
        if (w.totalKcal != null && !again) return
        val engine = RecoveryEngine(dao)
        val date = Instant.ofEpochSecond(w.from).atZone(zone).toLocalDate()
        // Worked out afresh, not fitted to the total saved with the profile before.
        val minutes = engine.phoneWorkoutKcal(
            w.copy(activeKcal = null, totalKcal = null), profile, profile.age(date), restingHr(w.from, zone), engine.maxHeartRate(profile, date, zone), engine.vo2max(profile, date, zone), end,
        ) ?: return
        val kcal = WorkoutRecorder.kcal(minutes)
        dao.updateAppWorkoutKcal(w.start, kcal.active, kcal.total)
    }

    /** Daytime resting HR at [t]: the strap's daily values over [Recovery.RHR_DAYS], else [DEFAULT_RESTING_HR]. */
    suspend fun restingHr(t: Long, zone: ZoneId = ZoneId.systemDefault()): Float = Recovery.daytimeRestingHr(
        dao.restingHrIn(t - Recovery.RHR_DAYS * DAY, t, zone).associate { Days.start(it.day, zone) to it.bpm.toFloat() },
        emptyMap(), dayStart(t, zone),
    ) ?: DEFAULT_RESTING_HR

    /**
     * Works out and saves every finished phone workout's figures again, oldest first so each one's activity class and
     * load trend read the earlier ones as now worked out. The workouts done.
     *
     * Each workout under [lock], as in [takeStrapEffects]: read again inside it and worked out from the row as it
     * stands, so a trim made meanwhile isn't written over with figures for its old range.
     */
    suspend fun recomputeAll(profile: UserProfile, lock: Mutex): Int {
        var n = 0
        for (listed in dao.appWorkoutsAll().sortedBy { it.start }) {
            val done = lock.withLock {
                val w = dao.appWorkoutOnce(listed.start)?.takeIf { it.end != null } ?: return@withLock false
                val hr = dao.appWorkoutHeartRateOnce(w.start).takeIf { it.isNotEmpty() } ?: return@withLock false
                val e = catching { withEffect(w, hr, profile) }
                    .onFailure { android.util.Log.w(TAG, "workout effect: ${w.start} not worked out again: $it") }
                    .getOrNull()?.takeIf { it.epoc != null } ?: return@withLock false
                dao.updateAppWorkoutEffect(
                    w.start, e.epoc, e.aerobicTrainingEffect, e.recoveryMinutes, e.vo2max, e.anaerobicTrainingEffect, e.effectFromStrap,
                    e.ownRecoveryMinutes,
                )
                true
            }
            if (done) n++
        }
        return n
    }

    /** Works out and saves the figures of a finished workout that has none yet (one from before version 14). */
    suspend fun backfill(w: AppWorkout, profile: UserProfile) {
        if (w.end == null || w.epoc != null) return
        val hr = dao.appWorkoutHeartRateOnce(w.start)
        if (hr.isEmpty()) return
        val e = withEffect(w, hr, profile)
        // Nothing to save without a valid reading; writing anyway would only wake every reader of the table.
        if (e.epoc == null) return
        dao.updateAppWorkoutEffect(w.start, e.epoc, e.aerobicTrainingEffect, e.recoveryMinutes, e.vo2max, e.anaerobicTrainingEffect, e.effectFromStrap, e.ownRecoveryMinutes)
    }

    /**
     * (start, EPOC) of every workout in the [TREND_DAYS] before [w]: the phone's own, and the strap's load (Zepp saves
     * int EPOC as it, native 04 §2.1) unless a phone workout covers the same time, so nothing counts twice.
     */
    private suspend fun earlierLoads(w: AppWorkout, zone: ZoneId): List<Pair<Long, Float>> {
        val since = dayStart(w.from, zone) - (TREND_DAYS - 1) * DAY
        val phone = dao.appWorkoutsOnce(since, w.from).filter { it.start != w.start && it.end != null && it.from < w.from }
        val strap = dao.workoutsOnce(since, w.from).filter { s ->
            s.start < w.from && !Workouts.sameWorkout(s.start..s.end, Workouts.phoneRange(w, w.end!!)) &&
                phone.none { p -> Workouts.sameWorkout(s.start..s.end, Workouts.phoneRange(p, p.end!!)) }
        }
        return phone.mapNotNull { p -> p.epoc?.let { p.from to it } } + strap.mapNotNull { s -> s.trainingLoad?.let { s.start to it.toFloat() } }
    }

    /** Recovery still owed at [w]'s start from the latest earlier workouts, phone or strap, in minutes. */
    private suspend fun carriedMinutes(w: AppWorkout): Int {
        val since = w.from - (WorkoutEffect.MAX_RECOVERY_H * 3600).toLong()
        val phone = dao.appWorkoutsOnce(since, w.from).filter { it.start != w.start && it.end != null && it.end <= w.from }
            .mapNotNull { p -> p.recoveryMinutes?.let { p.end!! to it } }
        val strap = dao.sportEffectsOnce(since, w.from).mapNotNull { e -> e.recoveryMinutes?.let { e.end to it } }
        return (phone + strap).maxOfOrNull { (end, minutes) -> minutes - ((w.from - end) / 60).toInt() }?.coerceAtLeast(0) ?: 0
    }

    private fun dayStart(t: Long, zone: ZoneId) = Days.start(t, zone)

    private companion object {
        const val DAY = 86_400L
        /** Fitness takes 42 days to build (Recovery.CTL_DAYS); the activity class uses the last 28 of them. */
        const val TREND_DAYS = Recovery.CTL_DAYS
        const val DEFAULT_RESTING_HR = 60f
        const val TAG = "unstrap"
    }
}
