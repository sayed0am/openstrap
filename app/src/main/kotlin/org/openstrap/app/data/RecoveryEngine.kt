package org.openstrap.app.data

import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.settings.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Charge screens show for one day. */
data class RecoveryResult(
    val date: LocalDate,
    val readiness: Recovery.Readiness,
    val charge: Recovery.ChargeDay,
    /** Readiness for the 7 days ending on the selected one (null where there was no sleep). */
    val readinessTrend: List<Pair<LocalDate, Int?>>,
    /** Training load (TRIMP) so far on the selected day, [workoutLoad] + [everydayLoad], and the usual (chronic) daily load. */
    val load: Float,
    val chronicLoad: Float?,
    val restingHr: Float,
    val maxHr: Float,
    /** True when max HR came from the user's age rather than a fallback. */
    val maxHrFromAge: Boolean,
    val stressThreshold: Recovery.StressThreshold,
    /** The main sleep that ended on [date], as the model saw it; null without one. */
    val tonight: Recovery.Night? = null,
    /** For the Breathing card: tonight's desaturation events per hour against the user's own nights. */
    val breathingDisturbances: Recovery.BreathingDisturbances? = null,
    /** Acute:chronic load ratio for the Load screen (contested; not part of Readiness). */
    val acwr: Float? = null,
    /** Sleep need in minutes (by age, or the user's own). */
    val sleepNeed: Int = Recovery.SLEEP_NEED_MINUTES,
    /** Sleep Regularity Index over the last 7 day-pairs, or the midpoint fallback. */
    val regularity: SleepRegularity.Result? = null,
    /** The sleep-health count for the night that ended on [date]. */
    val sleepHealth: SleepRegularity.Health? = null,
    /** Fitness (CTL), fatigue (ATL) and form at the start of [date], from worn days' loads. */
    val loadTrend: Recovery.LoadTrend? = null,
    /** Strain 0-21 from [load], and where [load] sits among the last 90 days (0-100). */
    val strain: Float = 0f,
    val strainPercentile: Int? = null,
    /** True when max HR is the user's own tested value. */
    val maxHrTested: Boolean = false,
    /** Steps, MVPA and distance for [date]. */
    val activity: Activity.Day? = null,
    /** Energy expenditure for [date]; null without weight, height and age. */
    val energy: EnergyExpenditure.Day? = null,
    /** Non-exercise VO2max (Jurca 2005), recomputed weekly; or why it's withheld. */
    val vo2max: Vo2max.Estimate? = null,
    val vo2maxWithheld: Vo2max.Withheld? = null,
    /** [load] split: minutes inside recorded workouts, and moving minutes outside them ([Recovery.dailyLoad]). */
    val workoutLoad: Float = 0f,
    val everydayLoad: Float = 0f,
    /** When you usually go to sleep ([RecoveryEngine.usualBedtime]); null before any main sleep. */
    val usualBedtime: java.time.LocalTime? = null,
    /**
     * The daily Load of each worn day in the [RecoveryEngine.RECENT_LOAD_DAYS] before [date], oldest first: the same
     * loads [chronicLoad] is worked out from. Days the strap was mostly off are absent.
     */
    val dailyLoads: List<Pair<LocalDate, Float>> = emptyList(),
)

/** Loads what [Recovery] needs from the database and runs it. All work happens off the main thread by the caller. */
class RecoveryEngine(private val dao: HealthDao) {

    /**
     * [RecoveryResult.usualBedtime] for [date] without the whole model: the same main sleeps over the same nights
     * [compute] reads. For the widgets' moment (widgets spec 2.2), which the widgets that don't show Recovery need too.
     */
    suspend fun usualBedtime(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): java.time.LocalTime? {
        val day = DayWindow.of(date, zone)
        val from = DayWindow.of(date.minusDays(Recovery.BASELINE_NIGHTS.toLong() + 1), zone).from
        val mains = mainsByMorning(dao.sleepSessionsOnce(from, day.to), zone)
        return usualBedtime(mains.filterKeys { it <= day.from }.values, date, zone)
    }

    suspend fun compute(date: LocalDate, profile: UserProfile, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000): RecoveryResult {
        val day = DayWindow.of(date, zone)
        val from = DayWindow.of(date.minusDays(Recovery.BASELINE_NIGHTS.toLong() + 1), zone).from
        val to = day.to

        // Load history (CTL, Strain's "vs your usual", max HR) reaches further back than the nightly baselines.
        val loadFrom = DayWindow.of(date.minusDays(LOAD_DAYS.toLong()), zone).from
        val activityAll = dao.activityOnce(loadFrom, to)
        val hrAll = minuteHeartRate(loadFrom, to, activityAll)
        val hr = hrAll.filter { it.first >= from }
        // Naps count with the user's own times when they changed them.
        val sessions = SleepEdits.apply(dao.sleepSessionsOnce(from, to), dao.sleepEditsOnce())
        val hrv = dao.hrvOnce(from, to)
        val skin = dao.skinTemperatureOnce(from, to)
        val breath = dao.breathRateOnce(from, to)
        val strapResting = dao.restingHrOnce(from, to)
        val pai = dao.paiOnce(from, to).lastOrNull()
        val sleepSpo2 = dao.sleepSpo2Once(from, to)
        // A night's summary uses the sleep result's day rule: the date of belongTime + 2 h.
        val osaByMorning = dao.osaResultsOnce(from - 86_400, to).associateBy {
            Instant.ofEpochSecond(it.belongTime + 2 * 3600).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        }

        val mains = mainsByMorning(sessions, zone)
        val strapNights = dao.strapReadinessOnce(from, to).associateBy { it.day }
        // Naps count toward a day's total sleep for the debt; a nap belongs to the day it started.
        val napsByDay = sessions.filter { it.nap }.groupBy { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond() }
            .mapValues { (_, v) -> v.sumOf { asleepMinutes(it) } }
        val nights = mains.map { (morning, s) ->
            val stages = dao.sleepStagesOnce(s.start).map { Triple(it.start, it.end, it.stage) }
            night(morning, s, hr, hrv, skin, breath, sleepSpo2, osaByMorning[morning]).copy(
                strapHrv = strapNights[morning]?.sleepHrv?.toFloat(),
                strapRestingHr = strapNights[morning]?.sleepRestingHr?.toFloat(),
                wasoMinutes = Recovery.wasoMinutes(stages),
                napMinutes = napsByDay[morning] ?: 0,
            )
        }.sortedBy { it.day }

        // Daytime resting HR: the strap's daily values when it has them, else the nightly lowest plus an offset.
        val restingHr = Recovery.daytimeRestingHr(
            strapResting.associate { it.day to it.bpm.toFloat() },
            nights.mapNotNull { n -> n.restingHr?.let { n.day to it } }.toMap(),
            day.from,
        ) ?: 60f
        val age = profile.age()
        fun dayOf(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        val maxHr = maxHeartRate(profile, hrAll, pai?.maxHeartRate, loadFrom, to, zone)
        val female = profile.sex == UserProfile.Sex.Female

        // Daily training load for the days before the selected one, skipping days the strap was mostly off. Workouts
        // (phone and strap) count every minute, on the workout's own heart rate; outside them only moving minutes do.
        val sleepRanges = sessions.map { it.start..it.end }
        val asleep = { t: Long -> sleepRanges.any { t in it } }
        val workoutHr = workoutHeartRates(loadFrom, to, now)
        val workoutRanges = workoutHr.map { it.first }
        val loadHr = Recovery.withWorkoutMinutes(hrAll, workoutHr.map { it.second })
        val stepsByMinute = activityAll.associate { it.timestamp / 60 * 60 to it.steps }
        val dailyLoad = { series: List<Pair<Long, Int>> -> Recovery.dailyLoad(series, restingHr, maxHr, female, workoutRanges, asleep) { stepsByMinute[it] } }
        val hrByDay = loadHr.groupBy { dayOf(it.first) }
        val loadDays = (LOAD_DAYS downTo 1).mapNotNull { back ->
            val d = date.minusDays(back.toLong())
            val w = DayWindow.of(d, zone)
            val series = hrByDay[d].orEmpty()
            if (series.size < MIN_WORN_MINUTES) null else w.from to dailyLoad(series).total
        }
        val loads = loadDays.map { it.second }

        // A likely cause for a body-signal alert: a hard training day before the morning.
        val hardDays = loadDays.indices.filter { k ->
            k >= 7 && Recovery.hardDay(loadDays[k].second, Recovery.ewmaLoads(loads.take(k)).second)
        }.map { loadDays[it].first }
        val causes = { morning: Long ->
            val before = morning - 86_400 + 3600
            listOfNotNull("a hard training day".takeIf { hardDays.any { d -> d in before - 7200..before + 7200 } })
        }
        val todaySplit = dailyLoad(hrByDay[date].orEmpty())
        val todayLoad = todaySplit.total

        val midnight = day.from
        val tonight = nights.lastOrNull { it.day == midnight }
        val sleepNeed = Recovery.sleepNeed(age, profile.sleepNeedMinutes)
        val readiness = Recovery.readiness(tonight, nights, loads, causes, sleepNeed)
        val trend = (6 downTo 0).map { back ->
            val d = date.minusDays(back.toLong())
            val m = DayWindow.of(d, zone).from
            d to (if (back == 0) readiness.score else Recovery.readiness(nights.lastOrNull { it.day == m }, nights, loadsBefore(loadDays, m), causes, sleepNeed).score)
        }

        // Charge: start a week and a day early, so the starting guess has washed out through the first night and the
        // week's wake values are there for a restart after a night with the strap off ([Recovery.charge]).
        val simFrom = midnight - (Recovery.WAKE_HISTORY + 1) * 86_400L
        // For a past day, run on past its end (as far as there is data) so an evening with the strap off can be seen to
        // last the night, which makes it a gap rather than a drift ([Recovery.charge]); the window is still the day.
        val simTo = minOf(day.to + 1 + LOOKAHEAD_SECONDS, now / 60 * 60 + 60)
        val stages = sessions.filter { it.end > simFrom && it.start < simTo }
            .flatMap { s -> dao.sleepStagesOnce(s.start).map { Triple(it.start, it.end, it.stage) } }
        val stress = dao.stressOnce(simFrom, simTo).map { it.timestamp to it.value }
        val simActivity = dao.activityOnce(simFrom, simTo)
        val steps = simActivity.associate { it.timestamp / 60 * 60 to it.steps }
        val offWrist = simActivity.filter { Recovery.offWrist(it.kind) }.mapTo(HashSet()) { it.timestamp / 60 * 60 }

        // Personal stress threshold from calm readings: awake, still, at resting heart rate and away from workouts.
        val calmFrom = DayWindow.of(date.minusDays(Recovery.STRESS_BASELINE_DAYS.toLong() - 1), zone).from
        // Minutes in or just after a workout are left out ([Recovery.stressExcluded]).
        val calmSteps = dao.activityOnce(calmFrom, to).associate { it.timestamp / 60 * 60 to it.steps }
        val stressExcluded = Recovery.stressExcluded(workoutRanges)
        val calm = Recovery.calmStress(dao.stressOnce(calmFrom, to).map { it.timestamp to it.value }, hr.toMap(), calmSteps, restingHr, maxHr, stressExcluded, asleep)
        val stressThreshold = Recovery.stressThreshold(calm) { Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toEpochDay() }
        // Heart rate after the day (the lookahead) only says when the strap went back on.
        val hrAhead = if (simTo > day.to + 1) minuteHeartRate(day.to + 1, simTo) else emptyList()
        val simHr = hr.filter { it.first in simFrom until simTo } + hrAhead.filter { it.first > day.to }
        // The strap's live wear pushes, where the app was connected to hear them; elsewhere the heart-rate gap rule stands.
        val wear = dao.wearEventsOnce(simFrom, simTo).map { it.timestamp to it.worn }
        val minutes = Recovery.minutes(simFrom, simTo, simHr, steps, stress, stages, workouts = workoutRanges, offWrist = offWrist, wear = wear)
        val hrvScore = readiness.contributors.firstOrNull { it.factor == Recovery.Factor.HrvBalance }?.score
        // Each main sleep's body-signal state, by the rule Readiness uses (it only looks backwards, so one pass over every
        // night gives each its own state); an Alert scales Charge from that sleep's start.
        val bodyByDay = BodySignals.evaluate(nights, causes).associate { it.day to it.state }
        val bodySignals = nights.map { it.start to (bodyByDay[it.day] ?: BodySignals.State.Normal) }
        val params = Recovery.ChargeParams(
            restingHr, maxHr, tonight?.restingHr, hrvScore, female, stressThreshold.value, bodySignals,
            mainSleepEnds = mains.values.map { it.end }.filter { it in simFrom until simTo },
            usualSleep = usualSleep(mains.filterKeys { it <= midnight }.values, date, zone),
        )
        val charge = Recovery.charge(minutes, params, day.from..day.to, start = 50f, mainSleepEnd = tonight?.end)

        // Activity: MVPA from cadence, then heart rate, over the 7 days ending on the selected one. Worked out for the
        // 14 days ending on it, the same way each day, so the week story can tell last week's days.
        val weekFrom = DayWindow.of(date.minusDays(ACTIVITY_DAYS - 1L), zone).from
        val workouts = dao.workoutsOnce(weekFrom, to)
        val minuteSteps = dao.activityOnce(weekFrom, to).filter { it.steps in 0..250 }.associate { it.timestamp / 60 * 60 to it.steps }
        val weekHr = hr.filter { it.first >= weekFrom }.toMap()
        val activityDays = (ACTIVITY_DAYS - 1 downTo 0).map { back ->
            val w = DayWindow.of(date.minusDays(back.toLong()), zone)
            // Minutes with any data; no row is missing, never zero.
            val times = (minuteSteps.keys.filter { it in w.from..w.to } + weekHr.keys.filter { it in w.from..w.to }).toSortedSet()
            times.map { t ->
                Activity.Minute(
                    t, minuteSteps[t] ?: 0, weekHr[t]?.let { Recovery.hrr(it, restingHr, maxHr) }, asleep(t),
                    workouts.any { t in it.start..it.end },
                )
            }
        }
        val dayMvpa = activityDays.map { Activity.mvpa(it) }
        val weekMvpa = dayMvpa.takeLast(7)
        val todayMinutes = activityDays.last()
        val activity = Activity.Day(
            steps = todayMinutes.sumOf { it.steps },
            mvpa = weekMvpa.last(),
            weekEquivalent = weekMvpa.sumOf { it.equivalent },
            distanceMeters = Activity.distanceMeters(todayMinutes, profile.heightCm),
            stepGoal = Activity.defaultStepGoal(age),
            byDay = dayMvpa.mapIndexed { i, m -> date.minusDays(ACTIVITY_DAYS - 1L - i) to m }.toMap(),
        )

        // VO2max: recomputed weekly, as of the latest Monday, from the nightly resting HR.
        val monday = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val (vo2, vo2Withheld) = Vo2max.estimate(
            profile, profile.age(monday), nights.mapNotNull { n -> n.validRestingHr?.let { n.day to it } }.toMap(),
            DayWindow.of(monday, zone).from,
        )

        val energy = energy(date, profile, restingHr, maxHr, vo2?.value?.toFloat(), zone, now)

        val loadTrend = if (loads.size >= 7) Recovery.fitnessFatigue(loads).last() else null
        val chronic = loadTrend?.ctl

        // Sleep regularity: 8 noon-to-noon grid days ending at noon today, so 7 day-pairs.
        val gridStart = date.minusDays(SleepRegularity.SRI_DAYS.toLong() + 1).atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
        val gridEnd = date.atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
        val gridStages = sessions.filter { it.end > gridStart && it.start < gridEnd }
            .flatMap { s -> dao.sleepStagesOnce(s.start).map { Triple(it.start, it.end, it.stage) } }
        val wornMinutes = hr.mapTo(HashSet()) { it.first / 60 * 60 }
        val grids = (0..SleepRegularity.SRI_DAYS).map { k ->
            val noon = date.minusDays(SleepRegularity.SRI_DAYS.toLong() + 1 - k).atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
            SleepRegularity.grid(noon, gridStages.filter { it.second > noon && it.first < noon + 86_400 }) { it in wornMinutes }
        }
        // Midpoints as minutes after local noon, so a night never wraps round midnight.
        fun minutesAfterNoon(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().toSecondOfDay().let { ((it / 60 - 720) % 1440 + 1440) % 1440 }
        val midpoints = mains.filterKeys { it > midnight - 7 * 86_400 + 3600 && it <= midnight }.values.map { minutesAfterNoon((it.start + it.end) / 2).toFloat() }
        val regularity = SleepRegularity.result(grids, midpoints)
        val sleepHealth = tonight?.let { n ->
            val main = mains[midnight]
            val stagesTonight = main?.let { dao.sleepStagesOnce(it.start).filter { st -> st.stage != org.openstrap.app.data.db.SleepStage.AWAKE } }.orEmpty()
            val period = stagesTonight.takeIf { it.isNotEmpty() }?.let { ((it.maxOf { st -> st.end } - it.minOf { st -> st.start }) / 60).toInt() }
            val mid = Instant.ofEpochSecond((n.start + n.end) / 2).atZone(zone).toLocalTime().toSecondOfDay() / 60
            SleepRegularity.health(n.asleepMinutes, n.wasoMinutes, period, regularity, age, mid)
        }
        return RecoveryResult(
            date, readiness, charge, trend, todayLoad, chronic, restingHr, maxHr, age != null, stressThreshold, tonight,
            Recovery.breathingDisturbances(tonight, nights), Recovery.acwr(loads), sleepNeed, regularity, sleepHealth,
            loadTrend, Recovery.strain(todayLoad), Recovery.loadPercentile(todayLoad, loads), profile.maxHeartRate != null && maxHr == profile.maxHeartRate.toFloat(),
            activity, energy, vo2, vo2Withheld, todaySplit.workout, todaySplit.everyday,
            usualBedtime(mains.filterKeys { it <= midnight }.values, date, zone),
            dailyLoads = loadDays.map { dayOf(it.first) to it.second }.filter { it.first >= date.minusDays(RECENT_LOAD_DAYS.toLong()) },
        )
    }

    /**
     * Max HR as of [date] by the same rule [compute] uses, for screens that don't run the whole model (workout zones):
     * the tested value, else Tanaka or the strap's PAI value, raised by observed peaks over [LOAD_DAYS].
     */
    suspend fun maxHeartRate(profile: UserProfile, date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Float {
        val to = DayWindow.of(date, zone).to
        val loadFrom = DayWindow.of(date.minusDays(LOAD_DAYS.toLong()), zone).from
        // Same PAI window as compute: the latest record within the nightly baseline span.
        val paiFrom = DayWindow.of(date.minusDays(Recovery.BASELINE_NIGHTS.toLong() + 1), zone).from
        val strapMax = dao.paiOnce(paiFrom, to).lastOrNull()?.maxHeartRate
        // A tested value wins outright, so it doesn't need 90 days of heart rate read back.
        val tested = profile.maxHeartRate
        if (tested != null) Recovery.maxHeartRate(profile.age(), strapMax, tested = tested).let { if (it == tested.toFloat()) return it }
        return maxHeartRate(profile, minuteHeartRate(loadFrom, to), strapMax, loadFrom, to, zone)
    }

    /**
     * Energy for [date] ([RecoveryResult.energy]) on the given resting and max HR and VO2max, reading only that day:
     * [compute] uses it, and so does the daily energy history for days nobody opened. Every minute with data (a step
     * record, heart rate, sleep, or a phone workout's minute) counts; no row is missing, never zero. A phone workout's
     * minutes take its own kcal ([phoneWorkoutKcal]), so the day counts what the workout says; a strap workout's are
     * the strap's kcal pro-rated.
     */
    suspend fun energy(
        date: LocalDate, profile: UserProfile, restingHr: Float, maxHr: Float, vo2max: Float?,
        zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000,
    ): EnergyExpenditure.Day? {
        val day = DayWindow.of(date, zone)
        val age = profile.age()
        val activity = dao.activityOnce(day.from, day.to)
        val minuteSteps = activity.filter { it.steps in 0..250 }.associate { it.timestamp / 60 * 60 to it.steps }
        val minuteHr = minuteHeartRate(day.from, day.to, activity).toMap()
        val sessions = SleepEdits.apply(dao.sleepSessionsOnce(day.from, day.to), dao.sleepEditsOnce())
        val strap = dao.workoutsOnce(day.from, day.to)
        val phone = HashMap<Long, Float>()
        for (w in dao.appWorkoutsOnce(day.from, day.to)) {
            phoneWorkoutKcal(w, profile, age, restingHr, maxHr, vo2max, now)?.forEach { if (it.t in day.from..day.to) phone[it.t] = it.kcal }
        }
        val sleep = sessions.flatMap { s -> (maxOf(s.start, day.from) / 60 * 60 until minOf(s.end, day.to + 1) step 60).toList() }.toSet()
        val asleep = { t: Long -> t in sleep || sessions.any { t in it.start..it.end } }
        val times = (minuteSteps.keys.filter { it in day.from..day.to } + minuteHr.keys.filter { it in day.from..day.to } + sleep + phone.keys).toSortedSet()
        return EnergyExpenditure.day(
            times.map { t ->
                val w = strap.firstOrNull { t in it.start until it.end }
                EnergyExpenditure.Minute(
                    t, minuteSteps[t], minuteHr[t]?.let { Recovery.hrr(it, restingHr, maxHr) }, asleep(t),
                    phone[t] ?: w?.calories?.let { kcal -> kcal / ((w.end - w.start) / 60f).coerceAtLeast(1f) },
                )
            },
            profile, age, vo2max,
        ) { Instant.ofEpochSecond(it).atZone(zone).hour }
    }

    /**
     * [w]'s kcal per minute ([WorkoutRecorder.kcalMinutes]) from its heart rate and the strap's steps, fitted to its
     * saved [AppWorkout.totalKcal] when it has one ([EnergyExpenditure.fitted]); a workout still recording, or one
     * from before version 20, as worked out now. Null without a profile.
     */
    suspend fun phoneWorkoutKcal(
        w: org.openstrap.app.data.db.AppWorkout, profile: UserProfile, age: Int?, restingHr: Float, maxHr: Float, vo2max: Float?,
        now: Long = System.currentTimeMillis() / 1000, hr: List<org.openstrap.app.data.db.AppWorkoutHeartRate>? = null,
    ): List<EnergyExpenditure.WorkoutMinute>? {
        val b = EnergyExpenditure.bmrPerMinute(profile, age) ?: return null
        val end = w.end ?: now
        val steps = dao.activityOnce(w.from - 60, end + 60).filter { it.steps in 0..250 }.associate { it.timestamp / 60 * 60 to it.steps }
        val minutes = WorkoutRecorder.kcalMinutes(w, hr ?: dao.appWorkoutHeartRateOnce(w.start), steps, restingHr, maxHr, profile, age, vo2max, now)
            ?: return null
        return EnergyExpenditure.fitted(minutes, w.totalKcal, b)
    }

    /**
     * VO2max as of [date] by the rule [compute] uses ([RecoveryResult.vo2max]): Jurca as of the latest Monday, on
     * the nightly resting HR of the two weeks before it. Null when withheld.
     */
    suspend fun vo2max(profile: UserProfile, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Float? {
        val monday = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val from = DayWindow.of(monday.minusDays(Vo2max.RHR_LOOKBACK_DAYS + 1L), zone).from
        val to = DayWindow.of(monday, zone).to
        val hr = minuteHeartRate(from, to)
        val hrv = dao.hrvOnce(from, to)
        val nights = mainsByMorning(dao.sleepSessionsOnce(from, to), zone).mapNotNull { (morning, s) ->
            night(morning, s, hr, hrv, emptyList(), emptyList(), emptyList(), null).validRestingHr?.let { morning to it }
        }.toMap()
        return Vo2max.estimate(profile, profile.age(monday), nights, DayWindow.of(monday, zone).from).first?.value?.toFloat()
    }

    /** Live heart rate is stored several times a minute; the model works in minutes. */
    private suspend fun minuteHeartRate(from: Long, to: Long, activity: List<org.openstrap.app.data.db.ActivityMinute>? = null): List<Pair<Long, Int>> =
        Summaries.bucketAverage(Summaries.heartRateSeries(dao.heartRateOnce(from, to), activity ?: dao.activityOnce(from, to)), 60)

    /**
     * Every workout overlapping [from]..[to], phone and strap, as (its time, its own heart rate per minute,
     * [Recovery.workoutMinutes]). A phone workout runs from its shown start to its end (now while recording), less its
     * pauses (the one under way included), one entry per active part; a strap one from its start to its end, so the
     * strap's 3-minute recovery recording after it is left out. Each workout's samples are reduced to minutes as soon
     * as they're read, so 90 days of per-second recordings are never all held at once.
     */
    private suspend fun workoutHeartRates(from: Long, to: Long, now: Long): List<Pair<LongRange, Map<Long, Int>>> {
        val phone = dao.appWorkoutsOnce(from, to).flatMap { w ->
            val ranges = Recovery.activeRanges(w, now).filter { !it.isEmpty() }
            if (ranges.isEmpty()) return@flatMap emptyList()
            val samples = dao.appWorkoutHeartRateOnce(w.start).map { it.timestamp to it.bpm }
            ranges.map { it to Recovery.workoutMinutes(it, samples) }
        }
        val strap = dao.workoutsOnce(from, to).mapNotNull { w ->
            val range = w.start until w.end
            if (range.isEmpty()) null else range to Recovery.workoutMinutes(range, dao.workoutHeartRateOnce(w.start).map { it.timestamp to it.bpm })
        }
        return phone + strap
    }

    private suspend fun maxHeartRate(profile: UserProfile, hrAll: List<Pair<Long, Int>>, strapMax: Int?, from: Long, to: Long, zone: ZoneId): Float {
        // Each day's highest real heart rate (filtered per-minute HR, and the strap's daily exercise maximum when the
        // minutes back it up); two separate days have to agree before it raises max HR, so one artefact can't.
        fun dayOf(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        val minuteMax = hrAll.groupBy { dayOf(it.first) }.mapValues { (_, m) -> Recovery.filteredMax(m) }
        val strapDaily = dao.dailyMaxHrOnce(from, to).map { dayOf(it.day) to it.bpm }
        val observedPeak = Recovery.observedPeak(Recovery.dailyMaxima(minuteMax, strapDaily))
        return Recovery.maxHeartRate(profile.age(), strapMax, observedPeak, profile.maxHeartRate)
    }

    private fun night(
        morning: Long,
        s: SleepSession,
        hr: List<Pair<Long, Int>>,
        hrv: List<org.openstrap.app.data.db.HrvSample>,
        skin: List<org.openstrap.app.data.db.SkinTemperature>,
        breath: List<org.openstrap.app.data.db.BreathRate>,
        spo2: List<org.openstrap.app.data.db.SleepSpo2Sample>,
        osa: org.openstrap.app.data.db.OsaResultRow?,
    ): Recovery.Night {
        val inSleep = { t: Long -> t in s.start..s.end }
        val sleepHr = hr.filter { inSleep(it.first) && it.second in Recovery.HR_MIN_BPM..Recovery.HR_MAX_BPM }
        val sleepBpm = sleepHr.map { it.second.toFloat() }
        val rmssd = hrv.filter { inSleep(it.timestamp) && it.milliseconds in Recovery.HRV_MIN_MS..Recovery.HRV_MAX_MS }.map { it.milliseconds.toFloat() }
        val asleep = asleepMinutes(s)
        val skinValues = skin.filter { inSleep(it.timestamp) }.map { it.celsius }
        val spo2Values = spo2.filter { inSleep(it.timestamp) }.map { it.percent.toFloat() }
        return Recovery.Night(
            day = morning,
            start = s.start,
            end = s.end,
            asleepMinutes = asleep,
            awakeMinutes = s.awakeMinutes,
            deepMinutes = s.deepMinutes,
            remMinutes = s.remMinutes,
            strapScore = s.score,
            hrv = rmssd.mean(),
            restingHr = Recovery.lowestAverage(sleepHr, 30, Recovery.RHR_WINDOW_MIN_MINUTES)?.second,
            rhrMeanSleep = sleepBpm.mean(),
            rhrP10 = Recovery.percentile(sleepBpm, 0.10f),
            hrvSamples = rmssd.size,
            hrMinutes = sleepHr.size,
            nadirTime = Recovery.lowestAverage(sleepHr, 10)?.first,
            // A handful of readings doesn't describe a night; the strap logs one a minute when worn.
            skinTemperature = skinValues.takeIf { it.size >= 30 }?.mean(),
            breathRate = breath.filter { inSleep(it.timestamp) }.map { it.perMinute.toFloat() }.mean(),
            spo2 = spo2Values.takeIf { it.size >= MIN_SPO2_READINGS }?.mean(),
            // Events per hour need a real night to divide by.
            odi = osa?.takeIf { asleep >= MIN_ODI_SLEEP_MINUTES }?.let { it.eventCount * 60f / asleep },
        )
    }

    companion object {
        /**
         * The usual main sleep on each night of the Charge simulation (the [Recovery.WAKE_HISTORY] + 1 nights ending on
         * [date]'s morning): the median start and end, as clock times, of the last [USUAL_SLEEP_NIGHTS] [mains];
         * midnight to 06:00 without any. Clock times count from noon, and each end from its own start, so a day sleep
         * after a night shift that runs past noon (08:00 to 16:00) is a range too rather than nothing.
         */
        internal fun usualSleep(mains: Collection<SleepSession>, date: LocalDate, zone: ZoneId): List<LongRange> {
            val recent = mains.sortedBy { it.end }.takeLast(USUAL_SLEEP_NIGHTS)
            fun afterNoon(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().toSecondOfDay().let { ((it - 43_200) % 86_400 + 86_400) % 86_400 }
            val start = recent.map { afterNoon(it.start).toFloat() }.median()?.toLong() ?: 43_200L
            val end = recent.map { (afterNoon(it.start) + (it.end - it.start).coerceIn(0L, 86_399L)).toFloat() }.median()?.toLong() ?: (18 * 3600L)
            if (end <= start) return emptyList()
            return (0..Recovery.WAKE_HISTORY + 1).map { back ->
                val noon = date.minusDays(back + 1L).atTime(12, 0).atZone(zone).toEpochSecond()
                noon + start..noon + end
            }
        }

        /**
         * The usual bedtime as a clock time: the start of [usualSleep]'s most recent range. Null without any [mains],
         * where [usualSleep] would only give its midnight stand-in.
         */
        internal fun usualBedtime(mains: Collection<SleepSession>, date: LocalDate, zone: ZoneId): java.time.LocalTime? =
            if (mains.isEmpty()) null
            else usualSleep(mains, date, zone).firstOrNull()?.let { Instant.ofEpochSecond(it.first).atZone(zone).toLocalTime() }

        /** One main sleep per morning ([morningOf]): the longest non-nap session ending that day. */
        internal fun mainsByMorning(sessions: List<SleepSession>, zone: ZoneId): Map<Long, SleepSession> =
            sessions.filter { !it.nap }
                .groupBy { morningOf(it, zone) }
                .mapValues { (_, v) -> v.maxBy { it.end - it.start } }

        /** Asleep minutes: deep + light + REM, else time in bed less awake. */
        fun asleepMinutes(s: SleepSession): Int = listOfNotNull(s.deepMinutes, s.lightMinutes, s.remMinutes).takeIf { it.isNotEmpty() }?.sum()
            ?: (((s.end - s.start) / 60).toInt() - (s.awakeMinutes ?: 0))

        /** Local midnight of the morning a sleep belongs to. */
        fun morningOf(s: SleepSession, zone: ZoneId): Long =
            if (s.day > 0) s.day else Instant.ofEpochSecond(s.end).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()

        /**
         * The daily loads a morning's readiness saw: those of [loadDays] (day start to load, oldest first) before
         * [morning], so a past day in the trend gets the "Previous day's load" it had then.
         */
        fun loadsBefore(loadDays: List<Pair<Long, Float>>, morning: Long): List<Float> = loadDays.filter { it.first < morning }.map { it.second }

        /** Days of load history: CTL's 42-day constant and Strain's 90-day "vs your usual". */
        const val LOAD_DAYS = 90

        /** Days of MVPA worked out, ending on the day shown: the week and the one before, for the week story. */
        const val ACTIVITY_DAYS = 14

        /** Days of daily Load the result carries, before the day shown, for the Load detail's Week and Month. */
        const val RECENT_LOAD_DAYS = 30
        /** A day needs this many minutes of heart rate before its training load counts. */
        const val MIN_WORN_MINUTES = 8 * 60
        /** Sleep SpO2 readings needed before a night's mean counts. */
        const val MIN_SPO2_READINGS = 5
        const val MIN_ODI_SLEEP_MINUTES = 120
        /** How far past a past day's end the Charge simulation runs, to see whether a not-worn evening lasted the night. */
        const val LOOKAHEAD_SECONDS = 14 * 3600L
        /** Main sleeps the usual sleep time for Charge's not-worn rule is the median of. */
        const val USUAL_SLEEP_NIGHTS = 14
    }
}

private fun List<Float>.mean(): Float? = if (isEmpty()) null else average().toFloat()
private fun List<Float>.median(): Float? = if (isEmpty()) null else sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
