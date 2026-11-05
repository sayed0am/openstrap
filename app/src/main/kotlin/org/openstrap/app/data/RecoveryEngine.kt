package org.openstrap.app.data

import org.openstrap.app.R
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.dailyMaxHrIn
import org.openstrap.app.data.db.restingHrIn
import org.openstrap.app.data.db.strapReadinessIn
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.app.i18n.Strings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

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
    val calories: EnergyExpenditure.Day? = null,
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
    /**
     * The strap's own score for [tonight], from its row for the strap session the night came from
     * ([SleepHistory.strapDay]), so a night moved to another day keeps it. Null without one.
     */
    val strapScore: Int? = null,
    /**
     * Recovery's usual: mean ± 1 SD of the score over the [History.NORMAL_DAYS] days ending on [date], each night as
     * its morning had it, as [History.normal] works out every normal. Null under [History.MIN_NORMAL_DAYS] scored days.
     */
    val readinessUsual: History.Normal? = null,
    /** Breathing disturbances (events an hour) for the [TREND_DAYS] nights ending on [date], oldest first, null without. */
    val disturbancesWeek: List<Pair<LocalDate, Float?>> = emptyList(),
    /** The usual band of breathing disturbances before tonight ([Recovery.disturbancesUsual]). */
    val disturbancesUsual: History.Normal? = null,
    /** A run on [date] much longer than any in the month before it ([Recovery.longRun]), for the Load screen. */
    val longRun: LongRun? = null,
)

/** Loads what [Recovery] needs from the database and runs it. All work happens off the main thread by the caller. */
class RecoveryEngine(private val dao: HealthDao) {
    /** Sleep as shown, with the user's edits ([SleepEdits]). */
    private val sleepSource = SleepSource(dao)

    /**
     * [RecoveryResult.usualBedtime] for [date] without the whole model: the same main sleeps over the same nights
     * [compute] reads. For the widgets' moment (widgets spec 2.2), which the widgets that don't show Recovery need too.
     */
    suspend fun usualBedtime(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): java.time.LocalTime? {
        val day = DayWindow.of(date, zone)
        val from = DayWindow.of(date.minusDays(Recovery.BASELINE_NIGHTS.toLong() + 1), zone).from
        val mains = mainsByMorning(sleepSource.sessionsOnce(from, day.to), zone)
        return usualBedtime(mains.filterKeys { it <= day.from }.values, date, zone)
    }

    /**
     * The whole model for [date], in three steps: [gather] reads every table once over the spans of [Span], [basis]
     * turns the rows into what each score stands on (nights, resting and max HR, daily loads), and each score is then
     * worked out from those and put together here. Only [calories] reads on its own, as it also runs without the rest.
     */
    suspend fun compute(date: LocalDate, profile: UserProfile, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000): RecoveryResult {
        val span = Span(date, zone, now)
        val reads = gather(span)
        val base = basis(span, reads, profile)

        val readiness = Recovery.readiness(base.tonight, base.nights, base.loads, base.causes, base.sleepNeed)
        // Each night's readiness as that morning had it: only the nights and loads before it, which [Span.from] reaches
        // back far enough for on the trend's first day too. Charge takes each sleep's HRV contributor from it.
        val byMorning = HashMap<Long, Recovery.Readiness>()
        base.tonight?.let { byMorning[it.day] = readiness }
        val readinessOf = { n: Recovery.Night ->
            byMorning.getOrPut(n.day) { Recovery.readiness(n, base.nights, loadsBefore(base.loadDays, n.day), base.causes, base.sleepNeed) }
        }
        val trend = (TREND_DAYS - 1 downTo 0).map { back ->
            val d = date.minusDays(back.toLong())
            val m = DayWindow.of(d, zone).from
            d to if (back == 0) readiness.score else base.nights.lastOrNull { it.day == m }?.let(readinessOf)?.score
        }
        // Recovery's usual: each night of the last 30 days as its morning had it, today's only with a night.
        val readinessUsual = History.normal(
            (History.NORMAL_DAYS - 1 downTo 0).mapNotNull { back ->
                val d = date.minusDays(back.toLong())
                val m = DayWindow.of(d, zone).from
                val score = if (back == 0) readiness.score?.takeIf { base.tonight != null }
                else base.nights.lastOrNull { it.day == m }?.let(readinessOf)?.score
                score?.toFloat()?.let { DayStat(d, it, it, it, 1) }
            },
            date,
        )
        val stressThreshold = stressThreshold(span, reads, base)
        val charge = charge(span, reads, base, readiness, stressThreshold, readinessOf)
        val activity = activity(span, reads, base, profile)

        // VO2max: recomputed weekly, as of the first day of the week ([Weeks]), from the nightly resting HR.
        val weekStart = Weeks.start(date)
        val (vo2, vo2Withheld) = Vo2max.estimate(
            profile, profile.age(weekStart), base.nights.mapNotNull { n -> n.validRestingHr?.let { n.day to it } }.toMap(),
            DayWindow.of(weekStart, zone).from,
        )

        val day = span.day
        val calories = calories(
            date, profile, base.restingHr, base.maxHr, vo2?.value?.toFloat(), zone, now,
            DayReads(
                reads.activity.within(day.from, day.to) { it.timestamp }, reads.heartRate.within(day.from, day.to) { it.timestamp },
                reads.sessions.filter { it.end in day.from..day.to },
                reads.strapWorkouts.filter { it.end >= day.from && it.start <= day.to },
            ),
        )

        val loads = base.loads
        val loadTrend = if (loads.size >= 7) Recovery.fitnessFatigue(loads).last() else null
        val chronic = loadTrend?.ctl
        val (regularity, sleepHealth) = sleepRegularity(span, reads, base)
        val todayLoad = base.todaySplit.total
        val maxHr = base.maxHr
        val tonight = base.tonight
        val nights = base.nights
        val mains = base.mains
        val midnight = span.midnight
        return RecoveryResult(
            date, readiness, charge, trend, todayLoad, chronic, base.restingHr, maxHr, base.age != null, stressThreshold, tonight,
            Recovery.breathingDisturbances(tonight, nights), Recovery.acwr(loads), base.sleepNeed, regularity, sleepHealth,
            loadTrend, Recovery.strain(todayLoad), Recovery.loadPercentile(todayLoad, loads), profile.maxHeartRate != null && maxHr == profile.maxHeartRate.toFloat(),
            activity, calories, vo2, vo2Withheld, base.todaySplit.workout, base.todaySplit.everyday,
            usualBedtime(mains.filterKeys { it <= midnight }.values, date, zone),
            dailyLoads = base.loadDays.map { span.dayOf(it.first) to it.second }
                .filter { it.first >= date.minusDays(RECENT_LOAD_DAYS.toLong()) },
            strapScore = tonight?.strapDay?.let(reads.strapNights::get)?.score,
            readinessUsual = readinessUsual,
            disturbancesWeek = (TREND_DAYS - 1 downTo 0).map { back ->
                val d = date.minusDays(back.toLong())
                val m = DayWindow.of(d, zone).from
                d to nights.lastOrNull { it.day == m }?.odi
            },
            disturbancesUsual = Recovery.disturbancesUsual(nights, midnight),
            longRun = Recovery.longRun(Recovery.runs(reads.phoneWorkouts, reads.strapWorkouts), span.day.from, span.day.to),
        )
    }

    /** The spans [compute] reads and scores for [date]. */
    private class Span(val date: LocalDate, val zone: ZoneId, val now: Long) {
        val day = DayWindow.of(date, zone)

        // The nightly baselines, from far enough back that the readiness trend's first day has its whole baseline too.
        val from = DayWindow.of(date.minusDays(Recovery.BASELINE_NIGHTS.toLong() + TREND_DAYS), zone).from
        val to = day.to

        // The sleep summaries are read from a day before [from].
        val osaFrom = from - 86_400

        // Load history (CTL, Strain's "vs your usual", max HR) reaches further back than the nightly baselines.
        val loadFrom = DayWindow.of(date.minusDays(LOAD_DAYS.toLong()), zone).from
        val midnight = day.from

        // Charge: start a week and a day early, so the starting guess has washed out through the first night and the
        // week's wake values are there for a restart after a night with the strap off ([Recovery.charge]).
        val simFrom = midnight - (Recovery.WAKE_HISTORY + 1) * 86_400L

        // For a past day, run on past its end (as far as there is data) so an evening with the strap off can be seen to
        // last the night, which makes it a gap rather than a drift ([Recovery.charge]). The window is still the day.
        val simTo = minOf(day.to + 1 + LOOKAHEAD_SECONDS, now / 60 * 60 + 60)

        // Personal stress threshold from calm readings: awake, still, at resting heart rate and away from workouts.
        val calmFrom = DayWindow.of(date.minusDays(Recovery.STRESS_BASELINE_DAYS.toLong() - 1), zone).from

        // The per-minute tables are read once each, over every span below, and sliced for each use: the load history,
        // the Charge run with its lookahead, the calm readings and the activity weeks all overlap.
        val readTo = maxOf(to, simTo)

        fun dayOf(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    }

    /** Every row [compute] reads, from [gather]. Sleep stages are read on first use, once per session. */
    @Suppress("LongParameterList") // One named field per read, built in one place by [gather].
    private class Reads(
        val activity: List<org.openstrap.app.data.db.ActivityMinute>,
        val heartRate: List<org.openstrap.app.data.db.HeartRateSample>,
        val stress: List<org.openstrap.app.data.db.StressSample>,
        val strapWorkouts: List<org.openstrap.app.data.db.Workout>,
        val phoneWorkouts: List<org.openstrap.app.data.db.AppWorkout>,
        /** Every workout's own heart rate ([workoutHeartRates]). */
        val workoutHr: List<Pair<LongRange, Map<Long, Int>>>,
        /** Hard workouts' longer stress exclusion ([Recovery.stressTails]). */
        val stressTails: List<LongRange>,
        /** Naps count with the user's own times when they changed them. */
        val sessions: List<SleepSession>,
        /**
         * The sleep as shown that ends after the day but began before the end of the Charge run ([Span.simTo]): on a past
         * day, the night that began that evening (data integrity item 13). Charge alone reads it.
         */
        val ahead: List<SleepSession>,
        /** The sleeps of [sessions] and [ahead] the user moved, with the strap's own session for each. */
        val moved: List<SleepEdits.Resolved>,
        val hrv: List<org.openstrap.app.data.db.HrvSample>,
        val skin: List<org.openstrap.app.data.db.SkinTemperature>,
        val breath: List<org.openstrap.app.data.db.BreathRate>,
        val strapResting: List<org.openstrap.app.data.db.RestingHeartRate>,
        val pai: org.openstrap.app.data.db.PaiSample?,
        /** Blood oxygen over the nights, from the all-day readings: the strap's sleep table reads a flat 100%. */
        val sleepSpo2: List<org.openstrap.app.data.db.Spo2Sample>,
        val osa: List<org.openstrap.app.data.db.OsaResultRow>,
        val strapNights: Map<LocalDate, org.openstrap.app.data.db.StrapReadinessRow>,
        val wear: List<org.openstrap.app.data.db.WearEventRow>,
        val dailyMaxHr: List<DailyMaxHr>,
        private val sleepSource: SleepSource,
    ) {
        // The nights, the charge, the regularity grid and tonight's sleep health all read stages: each session's once.
        private val stageReads = HashMap<SleepSession, List<org.openstrap.app.data.db.SleepStage>>()

        suspend fun stagesOf(s: SleepSession): List<org.openstrap.app.data.db.SleepStage> =
            stageReads.getOrPut(s) { sleepSource.stagesOnce(s) }
    }

    /** Step one of [compute]: every database read it needs, over the spans of [span]. */
    private suspend fun gather(span: Span): Reads {
        val strapWorkouts = dao.workoutsOnce(span.loadFrom, span.to)
        val phoneWorkouts = dao.appWorkoutsOnce(span.loadFrom, span.to)
        val resolved = sleepSource.resolvedOnce(span.from, span.to)
        // A past day's Charge runs on past its end, so a sleep that began before then and ended later counts as sleep.
        val after = if (span.simTo > span.to + 1) {
            sleepSource.resolvedOnce(span.to + 1, span.simTo + SleepEdits.SLEEP_MAX_SECONDS).filter { !it.deleted && it.start < span.simTo }
        } else emptyList()
        return Reads(
            activity = dao.activityOnce(span.loadFrom, span.readTo),
            heartRate = dao.heartRateOnce(span.loadFrom, span.readTo),
            stress = dao.stressOnce(minOf(span.simFrom, span.calmFrom), span.readTo),
            strapWorkouts = strapWorkouts,
            phoneWorkouts = phoneWorkouts,
            workoutHr = workoutHeartRates(span.now, phoneWorkouts, strapWorkouts),
            stressTails = Recovery.stressTails(phoneWorkouts, strapWorkouts),
            sessions = sleepSource.shown(resolved),
            ahead = sleepSource.shown(after),
            moved = (resolved + after).filter { it.moved && !it.deleted },
            hrv = dao.hrvOnce(span.from, span.to),
            skin = dao.skinTemperatureOnce(span.from, span.to),
            breath = dao.breathRateOnce(span.from, span.to),
            strapResting = dao.restingHrIn(span.from, span.to, span.zone),
            pai = dao.paiOnce(span.from, span.to).lastOrNull(),
            sleepSpo2 = dao.spo2Once(span.from, span.to),
            // The strap's nightly rows are keyed by its own day, which can be a day either side of the night's ([basis]).
            osa = dao.osaResultsOnce(span.osaFrom, span.to + STRAP_DAY_SLACK),
            strapNights = dao.strapReadinessIn(span.from - STRAP_DAY_SLACK, span.to + STRAP_DAY_SLACK, span.zone).associateBy { it.day },
            // The strap's live wear pushes, where the app was connected to hear them; elsewhere the heart-rate gap rule stands.
            wear = dao.wearEventsOnce(span.simFrom, span.simTo),
            dailyMaxHr = dao.dailyMaxHrIn(span.loadFrom, span.to, span.zone),
            sleepSource = sleepSource,
        )
    }

    /** What every score in [compute] stands on, from [basis]. */
    @Suppress("LongParameterList") // One named field per input, built in one place by [basis].
    private class Basis(
        /** Per-minute heart rate over the nightly baselines' span. */
        val hr: List<Pair<Long, Int>>,
        val mains: Map<Long, SleepSession>,
        val nights: List<Recovery.Night>,
        val tonight: Recovery.Night?,
        val restingHr: Float,
        val maxHr: Float,
        val age: Int?,
        val female: Boolean,
        val sleepNeed: Int,
        val asleep: (Long) -> Boolean,
        val workoutRanges: List<LongRange>,
        /** Each worn day's load before the day shown (day start to load, oldest first), and the loads alone. */
        val loadDays: List<Pair<Long, Float>>,
        val loads: List<Float>,
        val todaySplit: Recovery.DailyLoad,
        val causes: (Long) -> List<String>,
    )

    /** Step two of [compute]: the nights, resting and max HR, and the daily loads, from what [gather] read. */
    private suspend fun basis(span: Span, reads: Reads, profile: UserProfile): Basis {
        val date = span.date
        val zone = span.zone
        val loadFrom = span.loadFrom
        val to = span.to
        val activityAll = reads.activity.within(loadFrom, to) { it.timestamp }
        val hrAll = minuteHeartRate(reads.heartRate.within(loadFrom, to) { it.timestamp }, activityAll)
        val hr = hrAll.filter { it.first >= span.from }
        val sessions = reads.sessions
        // A night's summary uses the sleep result's day rule: the date of belongTime + 2 h, the strap's day.
        val osaByStrapDay = reads.osa.associateBy {
            Days.date(it.belongTime + 2 * 3600, zone)
        }

        val mains = mainsByMorning(sessions, zone)
        val strapNights = reads.strapNights
        // The strap's nightly rows go with the main sleep from the strap's own session for their day, which an edit
        // never changes: by the day a night belongs to, one moved past midnight would lose them. When two main sleeps
        // come from one strap day, the longer has them.
        val strapOwners = mains.values.groupBy { SleepHistory.strapDay(it, zone) }.mapValues { (_, v) -> v.maxBy { it.end - it.start } }
        // Naps count toward a day's total sleep for the debt. A nap belongs to the day it ended, as every sleep.
        val napsByDay = sessions.filter { it.nap }.groupBy { Days.start(SleepHistory.nightOf(it, zone), zone) }
            .mapValues { (_, v) -> v.sumOf { asleepMinutes(it) } }
        val nights = mains.map { (morning, s) ->
            val stages = reads.stagesOf(s).map { Triple(it.start, it.end, it.stage) }
            val strapDay = SleepHistory.strapDay(s, zone).takeIf { strapOwners[it] == s }
            night(morning, s, hr, reads.hrv, reads.skin, reads.breath, reads.sleepSpo2, strapDay?.let(osaByStrapDay::get)).copy(
                strapDay = strapDay,
                strapHrv = strapDay?.let(strapNights::get)?.sleepHrv?.toFloat(),
                strapRestingHr = strapDay?.let(strapNights::get)?.sleepRestingHr?.toFloat(),
                wasoMinutes = Recovery.wasoMinutes(stages),
                napMinutes = napsByDay[morning] ?: 0,
            )
        }.sortedBy { it.day }

        // Daytime resting HR: the strap's daily values when it has them, else the nightly lowest plus an offset.
        val restingHr = Recovery.daytimeRestingHr(
            // Keyed by the day's local midnight here, as the nights are, so the two match day for day.
            reads.strapResting.associate { Days.start(it.day, zone) to it.bpm.toFloat() },
            nights.mapNotNull { n -> n.restingHr?.let { n.day to it } }.toMap(),
            span.day.from,
        ) ?: 60f
        // The age the scored day had, so a past day keeps the max HR and sleep need it had then.
        val age = profile.age(date)
        val maxHr = maxHeartRate(profile, age, hrAll, reads.pai?.maxHeartRate, reads.dailyMaxHr, zone)
        val female = profile.sex == UserProfile.Sex.Female

        // Daily training load for the days before the selected one, skipping days the strap was mostly off. Workouts
        // (phone and strap) count every minute, on the workout's own heart rate; outside them only moving minutes do.
        val sleepRanges = sessions.map { it.start..it.end }
        val asleep = { t: Long -> sleepRanges.any { t in it } }
        val workoutHr = reads.workoutHr
        val workoutRanges = workoutHr.map { it.first }
        val loadHr = Recovery.withWorkoutMinutes(hrAll, workoutHr.map { it.second })
        val stepsByMinute = activityAll.associate { it.timestamp / 60 * 60 to it.steps }
        val dailyLoad = { series: List<Pair<Long, Int>> -> Recovery.dailyLoad(series, restingHr, maxHr, female, workoutRanges, asleep) { stepsByMinute[it] } }
        val hrByDay = loadHr.groupBy { span.dayOf(it.first) }
        val loadDays = (LOAD_DAYS downTo 1).mapNotNull { back ->
            val d = date.minusDays(back.toLong())
            val series = hrByDay[d].orEmpty()
            val load = dailyLoad(series)
            if (Recovery.loadDayCounts(series.size, load)) DayWindow.of(d, zone).from to load.total else null
        }
        val loads = loadDays.map { it.second }

        // A likely cause for a body-signal alert: a hard training day before the morning, against the fitness (CTL)
        // up to the day before, the same chronic load Readiness's previous-day contributor uses.
        val trend = Recovery.fitnessFatigue(loads)
        val hardDays = loadDays.indices.filter { k ->
            k >= 7 && Recovery.hardDay(loadDays[k].second, trend[k - 1].ctl)
        }.map { loadDays[it].first }
        val causes = { morning: Long ->
            val before = morning - 86_400 + 3600
            val hard = hardDays.any { d -> d in before - 7200..before + 7200 }
            listOfNotNull(Strings.app.get(R.string.core_recovery_cause_hard_day).takeIf { hard })
        }
        return Basis(
            hr, mains, nights, nights.lastOrNull { it.day == span.midnight }, restingHr, maxHr, age, female,
            Recovery.sleepNeed(age, profile.sleepNeedMinutes), asleep, workoutRanges, loadDays, loads,
            dailyLoad(hrByDay[date].orEmpty()), causes,
        )
    }

    /** The personal stress threshold, from the calm readings of the last [Recovery.STRESS_BASELINE_DAYS] days. */
    private fun stressThreshold(span: Span, reads: Reads, base: Basis): Recovery.StressThreshold {
        val calmFrom = span.calmFrom
        val to = span.to
        // Minutes in or just after a workout are left out ([Recovery.stressExcluded]).
        val calmSteps = reads.activity.within(calmFrom, to) { it.timestamp }.associate { it.timestamp / 60 * 60 to it.steps }
        val stressExcluded = Recovery.stressExcluded(base.workoutRanges, reads.stressTails)
        val calmReadings = reads.stress.within(calmFrom, to) { it.timestamp }.map { it.timestamp to it.value }
        val calm = Recovery.calmStress(calmReadings, base.hr.toMap(), calmSteps, base.restingHr, base.maxHr, stressExcluded, base.asleep)
        return Recovery.stressThreshold(calm) { Instant.ofEpochSecond(it).atZone(span.zone).toLocalDate().toEpochDay() }
    }

    /**
     * The day's Charge, run from [Span.simFrom] with each sleep's body-signal state and its own HRV contributor, from
     * that morning's readiness ([readinessOf]).
     */
    private suspend fun charge(
        span: Span, reads: Reads, base: Basis, readiness: Recovery.Readiness, stressThreshold: Recovery.StressThreshold,
        readinessOf: (Recovery.Night) -> Recovery.Readiness,
    ): Recovery.ChargeDay {
        val simFrom = span.simFrom
        val simTo = span.simTo
        val day = span.day
        val stages = (reads.sessions + reads.ahead).filter { it.end > simFrom && it.start < simTo }
            .flatMap { s -> reads.stagesOf(s).map { Triple(it.start, it.end, it.stage) } }
        val stress = reads.stress.within(simFrom, simTo) { it.timestamp }.map { it.timestamp to it.value }
        val simActivity = reads.activity.within(simFrom, simTo) { it.timestamp }
        val steps = simActivity.associate { it.timestamp / 60 * 60 to it.steps }
        val offWrist = simActivity.filter { Recovery.offWrist(it.kind) }.mapTo(HashSet()) { it.timestamp / 60 * 60 }
        // Heart rate after the day (the lookahead) only says when the strap went back on.
        val hrAhead = if (simTo > day.to + 1) {
            minuteHeartRate(
                reads.heartRate.within(day.to + 1, simTo) { it.timestamp },
                reads.activity.within(day.to + 1, simTo) { it.timestamp },
            )
        } else emptyList()
        val simHr = base.hr.filter { it.first in simFrom until simTo } + hrAhead.filter { it.first > day.to }
        val wear = reads.wear.map { it.timestamp to it.worn }
        val minutes = Recovery.minutes(
            simFrom, simTo, simHr, steps, stress, stages, workouts = base.workoutRanges, offWrist = offWrist, wear = wear,
            stressTails = reads.stressTails,
        )
        val hrvScore = hrvContributor(readiness)
        // Each main sleep's body-signal state, by the rule Readiness uses (it only looks backwards, so one pass over every
        // night gives each its own state); an Alert scales Charge from that sleep's start.
        val bodyByDay = BodySignals.evaluate(base.nights, base.causes).associate { it.day to it.state }
        val bodySignals = base.nights.map { it.start to (bodyByDay[it.day] ?: BodySignals.State.Normal) }
        // Each main sleep in the run recharges against its own night's reference, the night after a past day's too.
        val refHr = base.hr + hrAhead.filter { it.first > day.to }
        val nightRestingHr = base.mains[span.midnight]?.let { chargeNightRestingHr(it, reads.moved, refHr) } ?: base.tonight?.restingHr
        val runMains = (base.mains.values + mainsByMorning(reads.ahead, span.zone).values).filter { it.end > simFrom && it.start < simTo }
        val params = Recovery.ChargeParams(
            base.restingHr, base.maxHr, nightRestingHr, hrvScore, base.female, stressThreshold.value, bodySignals,
            mainSleepEnds = base.mains.values.map { it.end }.filter { it in simFrom until simTo },
            usualSleep = usualSleep(base.mains.filterKeys { it <= span.midnight }.values, span.date, span.zone),
            sleepRestingHr = runMains.map { it.start to chargeNightRestingHr(it, reads.moved, refHr) },
            sleepHrvScore = base.nights.filter { it.end > simFrom && it.start < simTo }.map { it.start to hrvContributor(readinessOf(it)) },
        )
        return Recovery.charge(minutes, params, day.from..day.to, start = 50f, mainSleepEnd = base.tonight?.end, bedtime = base.tonight?.start)
    }

    /** The HRV balance contributor of [r] (0-100), which scales how well that night's sleep recharges. Null without one. */
    private fun hrvContributor(r: Recovery.Readiness): Int? = r.contributors.firstOrNull { it.factor == Recovery.Factor.HrvBalance }?.score

    /**
     * The reference Charge's sleep term measures each minute of the main sleep [s] against: its lowest 30-minute heart
     * rate as the strap detected the night. When the user moved its times ([moved]), that comes from the strap's own
     * session, so a trim that cuts off the calmest half hour doesn't make the sleep kept count as more restful. An
     * unmoved night keeps its own value, the one [night] works out as its resting heart rate.
     */
    private fun chargeNightRestingHr(s: SleepSession, moved: List<SleepEdits.Resolved>, hr: List<Pair<Long, Int>>): Float? {
        fun lowest(from: Long, to: Long): Float? {
            val sleepHr = hr.filter { it.first in from..to && it.second in Recovery.HR_MIN_BPM..Recovery.HR_MAX_BPM }
            return Recovery.lowestAverage(sleepHr, 30, Recovery.RHR_WINDOW_MIN_MINUTES)?.second
        }
        val strap = moved.firstOrNull { !it.strap.nap && it.start == s.start && it.end == s.end }?.strap
        return strap?.let { lowest(it.start, it.end) } ?: lowest(s.start, s.end)
    }

    /**
     * Activity: MVPA from cadence, then heart rate, over the 7 days ending on the selected one. Worked out for the
     * 14 days ending on it, the same way each day, so the week story can tell last week's days.
     */
    private fun activity(span: Span, reads: Reads, base: Basis, profile: UserProfile): Activity.Day {
        val date = span.date
        val zone = span.zone
        val to = span.to
        val weekFrom = DayWindow.of(date.minusDays(ACTIVITY_DAYS - 1L), zone).from
        val workouts = reads.strapWorkouts.filter { it.end >= weekFrom }
        val minuteSteps = reads.activity.within(weekFrom, to) { it.timestamp }.filter { it.steps in 0..Recovery.MAX_MINUTE_STEPS }
            .associate { it.timestamp / 60 * 60 to it.steps }
        val weekHr = base.hr.filter { it.first >= weekFrom }.toMap()
        val activityDays = (ACTIVITY_DAYS - 1 downTo 0).map { back ->
            val w = DayWindow.of(date.minusDays(back.toLong()), zone)
            // Minutes with any data; no row is missing, never zero.
            val times = (minuteSteps.keys.filter { it in w.from..w.to } + weekHr.keys.filter { it in w.from..w.to }).toSortedSet()
            times.map { t ->
                Activity.Minute(
                    t, minuteSteps[t] ?: 0, weekHr[t]?.let { Recovery.hrr(it, base.restingHr, base.maxHr) }, base.asleep(t),
                    workouts.any { t in it.start..it.end },
                )
            }
        }
        val dayMvpa = activityDays.map { Activity.mvpa(it) }
        val weekMvpa = dayMvpa.takeLast(7)
        val todayMinutes = activityDays.last()
        return Activity.Day(
            steps = todayMinutes.sumOf { it.steps },
            mvpa = weekMvpa.last(),
            weekEquivalent = weekMvpa.sumOf { it.equivalent },
            distanceMeters = Activity.distanceMeters(todayMinutes, profile.heightCm),
            stepGoal = Activity.defaultStepGoal(base.age),
            byDay = dayMvpa.mapIndexed { i, m -> date.minusDays(ACTIVITY_DAYS - 1L - i) to m }.toMap(),
        )
    }

    /** Sleep regularity over the last week, and the sleep-health count for tonight (null without a night). */
    private suspend fun sleepRegularity(span: Span, reads: Reads, base: Basis): Pair<SleepRegularity.Result, SleepRegularity.Health?> {
        val date = span.date
        val zone = span.zone
        val midnight = span.midnight
        val mains = base.mains
        // Sleep regularity: 8 noon-to-noon grid days ending at noon today, so 7 day-pairs.
        val gridStart = date.minusDays(SleepRegularity.SRI_DAYS.toLong() + 1).atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
        val gridEnd = date.atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
        val gridStages = reads.sessions.filter { it.end > gridStart && it.start < gridEnd }
            .flatMap { s -> reads.stagesOf(s).map { Triple(it.start, it.end, it.stage) } }
        val wornMinutes = base.hr.mapTo(HashSet()) { it.first / 60 * 60 }
        val grids = (0..SleepRegularity.SRI_DAYS).map { k ->
            val noon = date.minusDays(SleepRegularity.SRI_DAYS.toLong() + 1 - k).atTime(SleepRegularity.DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
            SleepRegularity.grid(noon, gridStages.filter { it.second > noon && it.first < noon + 86_400 }) { it in wornMinutes }
        }
        // Midpoints as minutes after local noon, so a night never wraps round midnight.
        fun minutesAfterNoon(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().toSecondOfDay().let { ((it / 60 - 720) % 1440 + 1440) % 1440 }
        val midpoints = mains.filterKeys { it > midnight - 7 * 86_400 + 3600 && it <= midnight }.values.map { minutesAfterNoon((it.start + it.end) / 2).toFloat() }
        val regularity = SleepRegularity.result(grids, midpoints)
        val sleepHealth = base.tonight?.let { n ->
            val main = mains[midnight]
            val stagesTonight = main?.let { m ->
                reads.stagesOf(m).filter { st -> st.stage != org.openstrap.app.data.db.SleepStage.AWAKE }
            }.orEmpty()
            val period = stagesTonight.takeIf { it.isNotEmpty() }?.let { ((it.maxOf { st -> st.end } - it.minOf { st -> st.start }) / 60).toInt() }
            val mid = Instant.ofEpochSecond((n.start + n.end) / 2).atZone(zone).toLocalTime().toSecondOfDay() / 60
            SleepRegularity.health(n.asleepMinutes, n.wasoMinutes, period, regularity, base.age, mid)
        }
        return regularity to sleepHealth
    }

    /**
     * Max HR as of [date] by the same rule [compute] uses, for screens that don't run the whole model (workout zones):
     * the tested value, else Tanaka or the strap's PAI value, raised by observed peaks over [LOAD_DAYS].
     */
    suspend fun maxHeartRate(profile: UserProfile, date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Float {
        val (loadFrom, to, strapMax) = maxHeartRateWindow(date, zone)
        // A tested value wins outright, so it doesn't need 90 days of heart rate read back.
        val tested = profile.maxHeartRate
        if (tested != null) {
            Recovery.maxHeartRate(profile.age(date), strapMax, tested = tested).let { if (it == tested.toFloat()) return it }
        }
        return maxHeartRate(profile, profile.age(date), minuteHeartRate(loadFrom, to), strapMax, dao.dailyMaxHrIn(loadFrom, to, zone), zone)
    }

    /**
     * Calories for [date] ([RecoveryResult.calories]) on the given resting and max HR and VO2max, reading only that day:
     * [compute] uses it, and so does the daily calories history for days nobody opened. Every minute with data (a step
     * record, heart rate, sleep, or a phone workout's minute) counts; no row is missing, never zero. A phone workout's
     * minutes take its own kcal ([phoneWorkoutKcal]), so the day counts what the workout says; a strap workout's are
     * the strap's kcal pro-rated.
     */
    suspend fun calories(
        date: LocalDate, profile: UserProfile, restingHr: Float, maxHr: Float, vo2max: Float?,
        zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis() / 1000,
    ): EnergyExpenditure.Day? {
        val day = DayWindow.of(date, zone)
        val reads = DayReads(
            dao.activityOnce(day.from, day.to), dao.heartRateOnce(day.from, day.to), sleepSource.sessionsOnce(day.from, day.to),
            dao.workoutsOnce(day.from, day.to),
        )
        return calories(date, profile, restingHr, maxHr, vo2max, zone, now, reads)
    }

    /** What [calories] reads of its day, so [compute] can hand over what it read already. */
    private class DayReads(
        val activity: List<org.openstrap.app.data.db.ActivityMinute>,
        val heartRate: List<org.openstrap.app.data.db.HeartRateSample>,
        val sessions: List<SleepSession>,
        val strapWorkouts: List<org.openstrap.app.data.db.Workout>,
    )

    private suspend fun calories(
        date: LocalDate, profile: UserProfile, restingHr: Float, maxHr: Float, vo2max: Float?, zone: ZoneId, now: Long, reads: DayReads,
    ): EnergyExpenditure.Day? {
        val day = DayWindow.of(date, zone)
        val age = profile.age(date)
        val minuteSteps = reads.activity.filter { it.steps in 0..Recovery.MAX_MINUTE_STEPS }
            .associate { it.timestamp / 60 * 60 to it.steps }
        val minuteHr = minuteHeartRate(reads.heartRate, reads.activity).toMap()
        val sessions = reads.sessions
        val strap = reads.strapWorkouts
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
        val steps = dao.activityOnce(w.from - 60, end + 60).filter { it.steps in 0..Recovery.MAX_MINUTE_STEPS }
            .associate { it.timestamp / 60 * 60 to it.steps }
        val minutes = WorkoutRecorder.kcalMinutes(w, hr ?: dao.appWorkoutHeartRateOnce(w.start), steps, restingHr, maxHr, profile, age, vo2max, now)
            ?: return null
        return EnergyExpenditure.fitted(minutes, w.totalKcal, b)
    }

    /**
     * VO2max as of [date] by the rule [compute] uses ([RecoveryResult.vo2max]): Jurca as of the first day of the
     * week ([Weeks]), on the nightly resting HR of the two weeks before it. Null when withheld.
     */
    suspend fun vo2max(
        profile: UserProfile,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        firstDay: java.time.DayOfWeek = Weeks.firstDay(),
    ): Float? {
        val weekStart = Weeks.start(date, firstDay)
        val from = DayWindow.of(weekStart.minusDays(Vo2max.RHR_LOOKBACK_DAYS + 1L), zone).from
        val to = DayWindow.of(weekStart, zone).to
        val hr = minuteHeartRate(from, to)
        val hrv = dao.hrvOnce(from, to)
        val nights = mainsByMorning(sleepSource.sessionsOnce(from, to), zone).mapNotNull { (morning, s) ->
            night(morning, s, hr, hrv, emptyList(), emptyList(), emptyList(), null).validRestingHr?.let { morning to it }
        }.toMap()
        return Vo2max.estimate(profile, profile.age(weekStart), nights, DayWindow.of(weekStart, zone).from).first?.value?.toFloat()
    }

    /** Live heart rate is stored several times a minute; the model works in minutes. */
    private suspend fun minuteHeartRate(from: Long, to: Long): List<Pair<Long, Int>> =
        minuteHeartRate(dao.heartRateOnce(from, to), dao.activityOnce(from, to))

    private fun minuteHeartRate(
        hr: List<org.openstrap.app.data.db.HeartRateSample>, activity: List<org.openstrap.app.data.db.ActivityMinute>,
    ): List<Pair<Long, Int>> =
        Summaries.bucketAverage(Summaries.heartRateSeries(hr, activity), 60)

    /**
     * Every workout overlapping [from]..[to], phone and strap, as (its time, its own heart rate per minute,
     * [Recovery.workoutMinutes]). A phone workout runs from its shown start to its end (now while recording), less its
     * pauses (the one under way included), one entry per active part; a strap one from its start to its end, so the
     * strap's 3-minute recovery recording after it is left out. Each workout's samples are reduced to minutes as soon
     * as they're read, so 90 days of per-second recordings are never all held at once.
     */
    private suspend fun workoutHeartRates(
        now: Long, phoneWorkouts: List<org.openstrap.app.data.db.AppWorkout>, strapWorkouts: List<org.openstrap.app.data.db.Workout>,
    ): List<Pair<LongRange, Map<Long, Int>>> {
        val phone = phoneWorkouts.flatMap { w ->
            val ranges = Recovery.activeRanges(w, now).filter { !it.isEmpty() }
            if (ranges.isEmpty()) return@flatMap emptyList()
            val samples = dao.appWorkoutHeartRateOnce(w.start).map { it.timestamp to it.bpm }
            ranges.map { it to Recovery.workoutMinutes(it, samples) }
        }
        val strap = strapWorkouts.mapNotNull { w ->
            val range = w.start until w.end
            if (range.isEmpty()) null else range to Recovery.workoutMinutes(range, dao.workoutHeartRateOnce(w.start).map { it.timestamp to it.bpm })
        }
        return phone + strap
    }

    /**
     * What both max heart rates read: the load history's start and the day's end, and the strap's own max from the
     * latest PAI record within the nightly baseline span (the same window [compute] reads it from).
     */
    private suspend fun maxHeartRateWindow(date: LocalDate, zone: ZoneId): Triple<Long, Long, Int?> {
        val to = DayWindow.of(date, zone).to
        val loadFrom = DayWindow.of(date.minusDays(LOAD_DAYS.toLong()), zone).from
        val paiFrom = DayWindow.of(date.minusDays(Recovery.BASELINE_NIGHTS.toLong() + 1), zone).from
        return Triple(loadFrom, to, dao.paiOnce(paiFrom, to).lastOrNull()?.maxHeartRate)
    }

    private fun maxHeartRate(
        profile: UserProfile, age: Int?, hrAll: List<Pair<Long, Int>>, strapMax: Int?, strapDaily: List<DailyMaxHr>, zone: ZoneId,
    ): Float {
        val peak = Recovery.observedPeak(dailyMaxima(hrAll, strapDaily, zone).values.toList())
        return Recovery.maxHeartRate(age, strapMax, peak, profile.maxHeartRate)
    }

    /**
     * Each day's highest real heart rate (filtered per-minute HR, and the strap's daily exercise maximum when the
     * minutes back it up, from [strapDaily]). Two separate days have to agree before it raises max HR, so one artefact
     * can't.
     */
    private fun dailyMaxima(hrAll: List<Pair<Long, Int>>, strapDaily: List<DailyMaxHr>, zone: ZoneId): Map<LocalDate, Int> {
        fun dayOf(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        val minuteMax = hrAll.groupBy { dayOf(it.first) }.mapValues { (_, m) -> Recovery.filteredMax(m) }
        return Recovery.dailyMaximaByDay(minuteMax, strapDaily.map { it.day to it.bpm })
    }

    /**
     * Max HR as [maxHeartRate] works it out, with where it comes from and, when your workouts raised it, the day
     * they did: for the Heart rate zones page.
     */
    suspend fun maxHeartRateExplained(profile: UserProfile, date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): MaxHeartRate {
        val (loadFrom, to, strapMax) = maxHeartRateWindow(date, zone)
        val tested = profile.maxHeartRate?.takeIf { it in Recovery.TESTED_MAX_RANGE }
        if (tested != null) return MaxHeartRate(tested, MaxHeartRate.Source.Set)
        val maxima = dailyMaxima(minuteHeartRate(loadFrom, to), dao.dailyMaxHrIn(loadFrom, to, zone), zone)
        val peak = Recovery.observedPeak(maxima.values.toList())
        val bpm = Recovery.maxHeartRate(profile.age(date), strapMax, peak).roundToInt()
        val source = Recovery.maxHeartRateSource(profile.age(date), strapMax, peak)
        return MaxHeartRate(bpm, source, Recovery.observedPeakDay(maxima).takeIf { source == MaxHeartRate.Source.Learned })
    }

    private fun night(
        morning: Long,
        s: SleepSession,
        hr: List<Pair<Long, Int>>,
        hrv: List<org.openstrap.app.data.db.HrvSample>,
        skin: List<org.openstrap.app.data.db.SkinTemperature>,
        breath: List<org.openstrap.app.data.db.BreathRate>,
        spo2: List<org.openstrap.app.data.db.Spo2Sample>,
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
         * midnight to 06:00 without any. Each end counts from its own start, so a day sleep after a night shift that
         * runs past noon (08:00 to 16:00) is a range too rather than nothing. Starts count from the clock time opposite
         * their circular mean ([circularMean]), so bedtimes either side of any hour stay together: either side of noon
         * too, where counting from noon split them between its two ends. A usual night (bedtimes around 23:00) counts
         * from about 11:00, which gives the same medians counting from noon did. Each range ends at the usual wake time
         * on its day, the day a sleep belongs to ([SleepHistory.nightOf]), so a sleep that starts after noon is placed
         * on the day it ends too.
         */
        internal fun usualSleep(mains: Collection<SleepSession>, date: LocalDate, zone: ZoneId): List<LongRange> {
            val recent = mains.sortedBy { it.end }.takeLast(USUAL_SLEEP_NIGHTS)
            fun clock(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().toSecondOfDay()
            // Bedtimes spread evenly round the clock have no mean: they count from noon, as all of them used to.
            val origin = circularMean(recent.map { clock(it.start) })?.let { Math.floorMod(it + HALF_DAY, DAY_SECONDS) }
                ?: HALF_DAY.takeIf { recent.isNotEmpty() }
            fun fromOrigin(t: Long) = Math.floorMod(clock(t) - (origin ?: 0), DAY_SECONDS).toLong()
            val firstStart = recent.map { fromOrigin(it.start).toFloat() }.median()?.toLong()
            val firstEnd = recent.map { (fromOrigin(it.start) + (it.end - it.start).coerceIn(0L, DAY_SECONDS - 1L)).toFloat() }
                .median()?.toLong()
            // The usual wake time by the clock and how long before it the usual sleep starts: 06:00 and 6 hours without any.
            val (wake, length) = if (origin != null && firstStart != null && firstEnd != null) {
                Math.floorMod(origin + firstEnd, DAY_SECONDS.toLong()) to firstEnd - firstStart
            } else {
                NO_SLEEP_WAKE to NO_SLEEP_WAKE
            }
            if (length <= 0) return emptyList()
            return (0..Recovery.WAKE_HISTORY + 1).map { back ->
                val end = date.minusDays(back.toLong()).atTime(LocalTime.ofSecondOfDay(wake)).atZone(zone).toEpochSecond()
                end - length..end
            }
        }

        /**
         * The circular mean of clock times [secondsOfDay] (seconds after midnight), so 23:50 and 00:10 average to
         * midnight rather than noon. Null without any, or when they spread evenly round the clock and have no mean.
         */
        internal fun circularMean(secondsOfDay: List<Int>): Int? {
            val angles = secondsOfDay.map { it * 2 * Math.PI / DAY_SECONDS }
            val sin = angles.sumOf { kotlin.math.sin(it) }
            val cos = angles.sumOf { kotlin.math.cos(it) }
            // None, or spread so evenly that the sum has no length: no mean.
            if (kotlin.math.hypot(sin, cos) <= NO_MEAN * secondsOfDay.size) return null
            return Math.floorMod(Math.round(kotlin.math.atan2(sin, cos) / (2 * Math.PI) * DAY_SECONDS).toInt(), DAY_SECONDS)
        }

        private const val DAY_SECONDS = 86_400
        private const val HALF_DAY = DAY_SECONDS / 2

        /** Without any sleep, the usual one ends at 06:00 and runs 6 hours: midnight to 06:00. */
        private const val NO_SLEEP_WAKE = 6 * 3600L

        /** A mean resultant shorter than this, per time, has no direction worth the name. */
        private const val NO_MEAN = 1e-6

        /**
         * The usual bedtime as a clock time: the start of [usualSleep]'s most recent range. Null without any [mains],
         * where [usualSleep] would only give its midnight stand-in.
         */
        internal fun usualBedtime(mains: Collection<SleepSession>, date: LocalDate, zone: ZoneId): java.time.LocalTime? =
            if (mains.isEmpty()) null
            else usualSleep(mains, date, zone).firstOrNull()?.let { Instant.ofEpochSecond(it.first).atZone(zone).toLocalTime() }

        /** [SleepHistory.mainSleeps] keyed by each day's local midnight: one main sleep per day, the day it ended. */
        internal fun mainsByMorning(sessions: List<SleepSession>, zone: ZoneId): Map<Long, SleepSession> =
            SleepHistory.mainSleeps(sessions, zone).mapKeys { (day, _) -> Days.start(day, zone) }

        /** Asleep minutes: deep + light + REM, else time in bed less awake. */
        fun asleepMinutes(s: SleepSession): Int = listOfNotNull(s.deepMinutes, s.lightMinutes, s.remMinutes).takeIf { it.isNotEmpty() }?.sum()
            ?: (((s.end - s.start) / 60).toInt() - (s.awakeMinutes ?: 0))

        /**
         * The daily loads a morning's readiness saw: those of [loadDays] (day start to load, oldest first) before
         * [morning], so a past day in the trend gets the "Previous day's load" it had then.
         */
        fun loadsBefore(loadDays: List<Pair<Long, Float>>, morning: Long): List<Float> = loadDays.filter { it.first < morning }.map { it.second }

        /** Days of the readiness trend, ending on the day shown. */
        const val TREND_DAYS = 7

        /** Days of load history: CTL's 42-day constant and Strain's 90-day "vs your usual". */
        const val LOAD_DAYS = 90

        /** Days of MVPA worked out, ending on the day shown: the week and the one before, for the week story. */
        const val ACTIVITY_DAYS = 14

        /** Days of daily Load the result carries, before the day shown, for the Load detail's Week and Month. */
        const val RECENT_LOAD_DAYS = 30
        /** Sleep SpO2 readings needed before a night's mean counts. */
        const val MIN_SPO2_READINGS = 5
        const val MIN_ODI_SLEEP_MINUTES = 120
        /** How far past a past day's end the Charge simulation runs, to see whether a not-worn evening lasted the night. */
        const val LOOKAHEAD_SECONDS = 14 * 3600L
        /** Main sleeps the usual sleep time for Charge's not-worn rule is the median of. */
        const val USUAL_SLEEP_NIGHTS = 14
        /** How far past the nights read the strap's nightly rows are read, as its day can be either side of theirs. */
        private const val STRAP_DAY_SLACK = 2 * 86_400L
    }
}

/**
 * The rows of this list, sorted by [time], that fall in [from, to] with both ends in, as a `BETWEEN` read of that span
 * would return them.
 */
internal fun <T> List<T>.within(from: Long, to: Long, time: (T) -> Long): List<T> {
    fun firstAtOrAfter(t: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (time(get(mid)) < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
    val a = firstAtOrAfter(from)
    val b = if (to == Long.MAX_VALUE) size else firstAtOrAfter(to + 1)
    return if (b <= a) emptyList() else subList(a, b)
}

private fun List<Float>.mean(): Float? = if (isEmpty()) null else average().toFloat()
private fun List<Float>.median(): Float? = Stats.median(this)
