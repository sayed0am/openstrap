package org.openstrap.app.data

import org.openstrap.app.data.settings.UserProfile

/**
 * Daily energy expenditure, minute by minute, anchored on the user's own BMR (docs/algorithms/evidence-review.md,
 * "Energy expenditure"). Mifflin-St Jeor came within 10 % of measured resting metabolic rate in more people than other
 * equations (Frankenfield et al. 2005). Against doubly labelled water, HR-only estimates were less precise than
 * motion (Brage et al. 2015), and we have per-minute steps rather than acceleration, so the model is motion first
 * with a narrow, labelled heart-rate branch. Pure Kotlin, like [Recovery].
 *
 * Established for BMR; contested for everything above it. The 0.95, 1.15 and 1.3 multipliers are conventions, not 2024
 * Compendium values (sleeping and sitting quietly are both 1.0 MET there).
 */
object EnergyExpenditure {

    /** Mifflin-St Jeor sex terms, kcal/day: male +5, female −161, unset −78 (their midpoint). */
    const val BMR_MALE = 5f
    const val BMR_FEMALE = -161f
    const val BMR_UNSET = -78f

    /** Multipliers on the per-minute BMR (b) for minutes without a cadence MET. */
    const val SLEEP_FACTOR = 0.95f
    const val SEDENTARY_FACTOR = 1.15f
    const val LIGHT_FACTOR = 1.3f
    /** Minutes at or above this many steps use the cadence MET. */
    const val CADENCE_MET_STEPS = 60
    /** Running cadence: sustained (this and a neighbouring minute) at or above it uses the ACSM running equation. */
    const val RUNNING_CADENCE = 145

    /** HR branch ("HR-assisted"): awake, outside a workout, < 20 steps, ≥ 10 consecutive minutes at ≥ 40 % of reserve. */
    const val HR_BRANCH_MAX_STEPS = 20
    const val HR_BRANCH_HRR = 0.40f
    const val HR_BRANCH_RUN_MINUTES = 10
    const val HR_BRANCH_MAX_MET = 6f
    /** VO2max for the HR branch until there's an estimate of the user's own, ml/kg/min (UNVERIFIED default). */
    const val DEFAULT_VO2MAX = 35f
    /** Inside a phone workout heart rate is capped at this share of VO2max rather than at [HR_BRANCH_MAX_MET]. */
    const val WORKOUT_VO2_SHARE = 0.9f

    /** A day below this much wear is "partial"; PAL outside [PAL_LOW]..[PAL_HIGH] is flagged as implausible. */
    const val FULL_DAY_MINUTES = 20 * 60
    const val PAL_LOW = 1.2f
    const val PAL_HIGH = 2.5f

    /** Mifflin-St Jeor BMR, kcal/day: 10·W + 6.25·H − 5·A + sex term. */
    fun bmr(weightKg: Float, heightCm: Int, age: Int, sex: UserProfile.Sex?): Float =
        10f * weightKg + 6.25f * heightCm - 5f * age + when (sex) {
            UserProfile.Sex.Male -> BMR_MALE
            UserProfile.Sex.Female -> BMR_FEMALE
            null -> BMR_UNSET
        }

    /** kcal/min above rest for an activity of [met]: (MET − 1)·3.5·W/200. Anchoring on BMR avoids counting rest twice. */
    fun net(met: Float, weightKg: Float): Float = (met - 1f).coerceAtLeast(0f) * 3.5f * weightKg / 200f

    /**
     * METs for a walking cadence (CADENCE-Adults, Tudor-Locke et al. 2019): up to 60 years, 60 → 2.0, 100 → 3, 130 → 6,
     * linear between, held at 6 above 130. From 61: 100 → 3, 110 → 4, 120 → 5, then on to 6 at 130 (the last segment
     * is our extrapolation). A fixed-stride ACSM walking equation gives about 3.7 at 130 spm, far below the measured 6.
     */
    fun cadenceMet(cadence: Int, age: Int?): Float {
        val c = cadence.toFloat()
        val older = age != null && age >= 61
        val points = if (older) listOf(60f to 2f, 100f to 3f, 110f to 4f, 120f to 5f, 130f to 6f) else listOf(60f to 2f, 100f to 3f, 130f to 6f)
        if (c <= points.first().first) return points.first().second
        if (c >= points.last().first) return points.last().second
        val (a, b) = points.zipWithNext().first { (_, hi) -> c <= hi.first }
        return a.second + (b.second - a.second) * (c - a.first) / (b.first - a.first)
    }

    /** ACSM running on the level: VO2 = 3.5 + 0.2·v (v in m/min), as METs. */
    fun runningMet(cadence: Int, heightCm: Int): Float = (3.5f + 0.2f * cadence * Activity.stepLength(cadence, heightCm)) / 3.5f

    data class Minute(
        val t: Long,
        /** Null when there was no step record that minute (but the minute still had data, e.g. heart rate or sleep). */
        val steps: Int?,
        val hrr: Float?,
        val asleep: Boolean,
        /** The device's kcal for this minute when it falls inside a recorded workout (pro-rated). */
        val workoutKcal: Float? = null,
    )

    data class Day(
        val totalKcal: Float,
        /** BMR for the whole day, for reference. */
        val bmrKcal: Float,
        val minutesWithData: Int,
        /** Under [FULL_DAY_MINUTES] of data: the total covers only part of the day. */
        val partial: Boolean,
        /** TEE / BMR, on a full day. */
        val pal: Float?,
        /** PAL outside 1.2-2.5: likely a wear or profile problem. */
        val palImplausible: Boolean,
        val hrAssistedMinutes: Int,
        /** Total less b for each minute with data, each minute held at zero, so sleeping never subtracts. */
        val activeKcal: Float = 0f,
        /** [activeKcal] in workout minutes and in walking minutes (≥ [CADENCE_MET_STEPS] outside workouts); the rest is everyday. */
        val workoutKcal: Float = 0f,
        val walkingKcal: Float = 0f,
        val hourly: Hourly = Hourly(),
        /** The walking minutes behind [walkingKcal]: awake, outside workouts, at [CADENCE_MET_STEPS] or more. */
        val walkingMinutes: Int = 0,
    ) {
        val everydayKcal: Float get() = activeKcal - workoutKcal - walkingKcal
    }

    /** Active kcal for each local hour of the day (index 0..23), split by where it came from, as [Day] splits it. */
    data class Hourly(
        val workout: List<Float> = List(24) { 0f },
        val walking: List<Float> = List(24) { 0f },
        val everyday: List<Float> = List(24) { 0f },
    ) {
        val total: List<Float> get() = List(24) { workout[it] + walking[it] + everyday[it] }
    }

    /**
     * Energy for one day of [minutes] (ascending, only minutes with data, no row is missing, never zero). Null without
     * weight, height and age. No thermic effect of food is added. [hourOf] gives a minute's local hour for [Day.hourly].
     */
    fun day(
        minutes: List<Minute>, profile: UserProfile, age: Int?, vo2max: Float? = null,
        hourOf: (Long) -> Int = { Math.floorMod(it, 86_400L).toInt() / 3600 },
    ): Day? {
        val w = profile.weightKg ?: return null
        val h = profile.heightCm ?: return null
        val a = age ?: return null
        val bmrDay = bmr(w, h, a, profile.sex)
        val b = bmrDay / 1440f
        val vo2Mets = (vo2max ?: DEFAULT_VO2MAX) / 3.5f
        val n = minutes.size
        // The HR branch's qualifying runs.
        val hrRun = BooleanArray(n)
        var i = 0
        while (i < n) {
            val ok = { k: Int ->
                val m = minutes[k]
                !m.asleep && m.workoutKcal == null && (m.steps ?: 0) < HR_BRANCH_MAX_STEPS && (m.hrr ?: 0f) >= HR_BRANCH_HRR
            }
            if (!ok(i)) { i++; continue }
            var j = i
            while (j + 1 < n && ok(j + 1) && minutes[j + 1].t - minutes[j].t == 60L) j++
            if (j - i + 1 >= HR_BRANCH_RUN_MINUTES) for (k in i..j) hrRun[k] = true
            i = j + 1
        }

        var total = 0.0
        var hrAssisted = 0
        val workout = FloatArray(24)
        val walking = FloatArray(24)
        val everyday = FloatArray(24)
        var walkingMinutes = 0
        for ((k, m) in minutes.withIndex()) {
            var ee = when {
                m.workoutKcal != null -> maxOf(m.workoutKcal, b)
                m.asleep -> SLEEP_FACTOR * b
                else -> motionEe(minutes, k, b, w, h, a)
            }
            if (hrRun[k]) {
                val met = minOf(HR_BRANCH_MAX_MET, 1f + m.hrr!! * (vo2Mets - 1f))
                val hrEe = b + net(met, w)
                if (hrEe > ee) { ee = hrEe; hrAssisted++ }
            }
            total += ee
            // Active: above rest, never below it. A minute's source is what set its kcal, so an HR-assisted one is everyday.
            val active = (ee - b).coerceAtLeast(0f)
            val hour = hourOf(m.t).coerceIn(0, 23)
            when {
                m.workoutKcal != null -> workout[hour] += active
                !m.asleep && (m.steps ?: 0) >= CADENCE_MET_STEPS -> { walking[hour] += active; walkingMinutes++ }
                else -> everyday[hour] += active
            }
        }
        val partial = n < FULL_DAY_MINUTES
        val pal = if (partial) null else (total / bmrDay).toFloat()
        return Day(
            total.toFloat(), bmrDay, n, partial, pal, pal != null && (pal < PAL_LOW || pal > PAL_HIGH), hrAssisted,
            activeKcal = workout.sum() + walking.sum() + everyday.sum(), workoutKcal = workout.sum(), walkingKcal = walking.sum(),
            hourly = Hourly(workout.toList(), walking.toList(), everyday.toList()), walkingMinutes = walkingMinutes,
        )
    }

    /**
     * kcal for minute [k] of [minutes] from its steps alone, awake: the cadence or running MET from
     * [CADENCE_MET_STEPS], light with some steps or a stepping neighbour, else sedentary.
     */
    private fun motionEe(minutes: List<Minute>, k: Int, b: Float, w: Float, h: Int, a: Int): Float {
        val steps = minutes[k].steps ?: 0
        return when {
            steps >= CADENCE_MET_STEPS ->
                b + net(if (steps >= RUNNING_CADENCE && neighbourAtLeast(minutes, k, RUNNING_CADENCE)) runningMet(steps, h) else cadenceMet(steps, a), w)
            steps > 0 || neighbourAtLeast(minutes, k, 1) -> LIGHT_FACTOR * b
            else -> SEDENTARY_FACTOR * b
        }
    }

    /** One minute of a phone workout: its kcal, the part above rest, and whether heart rate set it rather than steps. */
    data class WorkoutMinute(val t: Long, val kcal: Float, val activeKcal: Float, val hrLed: Boolean)

    /**
     * kcal for each minute of a phone workout. [minutes] are its active minute starts, ascending, pauses left out;
     * [hrr] is every heart rate sample in them as (time, reserve), live about once a second and history minutes spread
     * across theirs; [steps] is steps per minute start. Each minute takes the larger of motion ([motionEe]) and heart
     * rate, b + net(1 + x·(VO2max/3.5 − 1)) per sample, capped at [WORKOUT_VO2_SHARE] of VO2max and averaged over the
     * minute. No 10-minute rule: in a workout, heart rate is the effort. Null without weight, height and age.
     */
    fun workoutMinutes(
        minutes: List<Long>, hrr: List<Pair<Long, Float>>, steps: Map<Long, Int>, profile: UserProfile, age: Int?, vo2max: Float? = null,
    ): List<WorkoutMinute>? {
        val w = profile.weightKg ?: return null
        val h = profile.heightCm ?: return null
        val a = age ?: return null
        val b = bmr(w, h, a, profile.sex) / 1440f
        val vo2Mets = (vo2max ?: DEFAULT_VO2MAX) / 3.5f
        val cap = WORKOUT_VO2_SHARE * vo2Mets
        val hrSum = HashMap<Long, Double>()
        val hrCount = HashMap<Long, Int>()
        for ((t, x) in hrr) {
            val m = Math.floorDiv(t, 60L) * 60
            hrSum[m] = (hrSum[m] ?: 0.0) + (b + net(minOf(cap, 1f + x * (vo2Mets - 1f)), w))
            hrCount[m] = (hrCount[m] ?: 0) + 1
        }
        val asMinutes = minutes.map { Minute(it, steps[it], null, asleep = false) }
        return asMinutes.mapIndexed { k, m ->
            val motion = motionEe(asMinutes, k, b, w, h, a)
            val hr = hrCount[m.t]?.let { (hrSum[m.t]!! / it).toFloat() }
            val kcal = maxOf(motion, hr ?: 0f)
            WorkoutMinute(m.t, kcal, (kcal - b).coerceAtLeast(0f), hr != null && hr > motion)
        }
    }

    /**
     * [minutes] with their active part scaled so they add up to [totalKcal], the workout's saved figure, so a day
     * counts exactly what the workout says even when its inputs (resting HR, a sync's steps) have moved since. Each
     * minute keeps its shape and stays at or above [b]. As they are without a saved total.
     */
    fun fitted(minutes: List<WorkoutMinute>, totalKcal: Float?, b: Float): List<WorkoutMinute> {
        if (totalKcal == null || minutes.isEmpty()) return minutes
        val active = (totalKcal - b * minutes.size).coerceAtLeast(0f)
        val now = minutes.sumOf { it.activeKcal.toDouble() }.toFloat()
        return minutes.map {
            val part = if (now > 0f) it.activeKcal * active / now else active / minutes.size
            it.copy(kcal = b + part, activeKcal = part)
        }
    }

    /** The per-minute BMR, b, for [profile] at [age]; null without weight, height and age. */
    fun bmrPerMinute(profile: UserProfile, age: Int?): Float? {
        val w = profile.weightKg ?: return null
        val h = profile.heightCm ?: return null
        val a = age ?: return null
        return bmr(w, h, a, profile.sex) / 1440f
    }

    private fun neighbourAtLeast(minutes: List<Minute>, i: Int, cadence: Int) = listOf(i - 1, i + 1).any { j ->
        val m = minutes.getOrNull(j) ?: return@any false
        kotlin.math.abs(m.t - minutes[i].t) <= 60 && (m.steps ?: 0) >= cadence
    }
}
