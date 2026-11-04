package org.openstrap.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.openstrap.app.data.db.DailyCaloriesRow
import org.openstrap.app.data.db.DailyPai
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.settings.UserProfile
import org.openstrap.protocol.services.StepTotals
import java.time.LocalDate
import java.time.ZoneId

/**
 * The [DayRepository] over Room, through [health]. [strapSteps] is the strap's own running step total, which today's
 * steps add when it's ahead of the synced minutes, through the app's one [liveSteps], so Today and the widgets count alike.
 */
// Most of its functions are one-line overrides of the interface's, which detekt counts too.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class RoomDayRepository(
    private val health: HealthRepository,
    private val liveSteps: LiveSteps,
    private val strapSteps: Flow<StepTotals?> = flowOf(null),
) : DayRepository {
    override fun daysWithData(): Flow<Set<LocalDate>> = health.daysWithData()
    override suspend fun hourlySteps(from: LocalDate, to: LocalDate): Map<LocalDate, List<Float>> = health.hourlySteps(from, to)
    override suspend fun recovery(date: LocalDate, profile: UserProfile): RecoveryResult = health.recovery(date, profile)
    override suspend fun healthspan(date: LocalDate, profile: UserProfile): Healthspan.Outcome = health.healthspan(date, profile)
    override fun changes(): Flow<Unit> = health.changes()
    override fun dailyPai(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DailyPai>> = health.dailyPai(from, to)
    override fun dailyCalories(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DailyCaloriesRow>> = health.dailyCalories(from, to)
    override fun dailySteps(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> = health.dailySteps(from, to)
    override fun personalBest(kind: String): Flow<org.openstrap.app.data.db.PersonalBest?> = health.personalBest(kind)
    override suspend fun mostStepsBefore(date: LocalDate): Int? = health.mostStepsBefore(date)
    override fun deletedSleep(): Flow<List<SleepEdits.Resolved>> = health.deletedSleep()
    override suspend fun setSleepTimes(strap: SleepSession, start: Long, end: Long) = health.setSleepTimes(strap, start, end)
    override suspend fun deleteSleep(strap: SleepSession) = health.deleteSleep(strap)
    override suspend fun restoreSleep(strap: SleepSession) = health.restoreSleep(strap)

    override fun day(date: LocalDate, isToday: Boolean): Flow<DayData> {
        val zone = ZoneId.systemDefault()
        val day = DayWindow.of(date)
        // The day's sleep is what ended in it ([SleepHistory.nightOf]). Its readings reach back to the evening before.
        val night = SleepHistory.readingsOf(date, zone)

        val activityAndHr = combine(health.activity(day), health.heartRate(day)) { act, hr -> act to hr }
        val vitals = combine(health.spo2(day), health.stress(day), health.hrv(day)) { spo2, stress, hrv -> Triple(spo2, stress, hrv) }
        val span = DayWindow(
            SleepHistory.endingOn(date.minusDays(SleepHistory.NIGHTS - 1L), zone).from, SleepHistory.endingOn(date, zone).to,
        )
        val extras = combine(health.skinTemperature(day), health.breathRate(night), health.latestPai(day.to)) { skin, breath, pai ->
            Triple(skin, breath, pai?.takeIf { it.timestamp >= day.from })
        }
        val sleepAndExtras = combine(nights(date, night, span, zone), extras, baselines(date)) { s, e, b -> Triple(s, e, b) }

        // The strap's own running total covers steps whose minute records were already taken by another app.
        val todayLive = if (isToday) strapSteps else flowOf(null)
        val actAndLive = combine(activityAndHr, todayLive) { a, l -> a to l }

        return combine(
            actAndLive, vitals, sleepAndExtras, weekly(date, zone), more(date, day, night),
        ) { (ah, live), (spo2, stress, hrv), (sleepPart, extraParts, base), weeklyParts, moreParts ->
            val (skin, breath, pai) = extraParts
            val (act, hr) = ah
            moreParts.copy(
                isToday = isToday,
                day = day,
                night = night,
                minutes = act,
                heartRate = hr,
                // Only today's count touches the shared anchor: another day's would move it off today.
                steps = Summaries.totalSteps(act).let { total -> if (isToday) liveSteps.today(date, total, live) else total },
                restingTrend = weeklyParts.resting,
                restingEstimated = weeklyParts.restingEstimated,
                hrvTrend = weeklyParts.hrv,
                sleep = sleepPart.sleep,
                nights = sleepPart.nights,
                nightHeartRate = sleepPart.nightHeartRate,
                sleepHrv = sleepPart.sleepHrv,
                sleepSkin = sleepPart.sleepSkin,
                osaResult = sleepPart.osaResult,
                spo2 = spo2,
                stress = stress,
                hrv = hrv,
                skin = skin,
                breath = breath,
                pai = pai,
                baselines = base,
            )
        }
    }

    /**
     * The night's oxygen and apnea readings, and the day's extended activity, highest heart rate and alerts. The night's
     * apnea summary comes with its sleep ([sleep]).
     */
    private fun more(date: LocalDate, day: DayWindow, night: DayWindow): Flow<DayData> = combine(
        combine(health.sleepSpo2(night), health.hrvSdnn(day), health.osaEvents(night)) { a, b, c -> Triple(a, b, c) },
        health.dailyMaxHr(date),
        health.activityExtended(day),
        health.healthAlerts(day),
    ) { (sleepSpo2, sdnn, events), maxHr, extended, alerts ->
        DayData(
            date = date,
            sleepSpo2 = sleepSpo2,
            hrvSdnn = sdnn,
            osaEvents = events,
            dailyMaxHeartRate = maxHr?.bpm,
            distanceMeters = extended.takeIf { it.isNotEmpty() }?.sumOf { it.distanceCm.toLong() }?.let { (it / CM_PER_M).toInt() },
            activeCalories = extended.takeIf { it.isNotEmpty() }?.sumOf { it.calories },
            alerts = alerts,
        )
    }

    /**
     * The day's sleep ([sleep]), the [SleepHistory.NIGHTS] nights over [span] with the vitals averaged over each, and
     * the night's own readings for the charts of the night-scoped details.
     */
    private fun nights(date: LocalDate, night: DayWindow, span: DayWindow, zone: ZoneId): Flow<DayData> = combine(
        sleep(date, span, zone),
        combine(health.hrv(span), health.skinTemperature(span)) { h, s -> h to s },
        combine(health.sleepSpo2(span), health.breathRate(span)) { o, b -> o to b },
        health.heartRate(night),
    ) { sleepParts, (hrv, skin), (spo2, breath), hr ->
        val (parts, sessions, osa) = sleepParts
        val main = SleepHistory.mainSleeps(sessions, zone)
        val list = (SleepHistory.NIGHTS - 1 downTo 0).map { ago ->
            val d = date.minusDays(ago.toLong())
            val s = main[d]
            // Averages over the sleep itself, not the whole night window.
            fun <T> during(samples: List<T>, time: (T) -> Long, value: (T) -> Float): Float? = s?.let { w ->
                samples.filter { time(it) in w.start..w.end }.map(value).takeIf { it.isNotEmpty() }?.average()?.toFloat()
            }
            SleepNight(
                date = d,
                session = s,
                hrv = during(hrv, { it.timestamp }, { it.milliseconds.toFloat() }),
                skinTemperature = during(skin, { it.timestamp }, { it.celsius }),
                spo2 = during(spo2, { it.timestamp }, { it.percent.toFloat() }),
                breathing = during(breath, { it.timestamp }, { it.perMinute.toFloat() }),
            )
        }
        val tonight = main[date]
        DayData(
            date = date,
            sleep = parts,
            nights = list,
            nightHeartRate = hr,
            sleepHrv = tonight?.let { w -> hrv.filter { it.timestamp in w.start..w.end } }.orEmpty(),
            sleepSkin = tonight?.let { w -> skin.filter { it.timestamp in w.start..w.end } }.orEmpty(),
            osaResult = osa,
        )
    }

    /** What [sleep] reads for a day: its sleep, the sessions shown over the nights' span, and the night's apnea summary. */
    private data class SleepParts(val day: SleepDay, val span: List<SleepSession>, val osaResult: OsaResultRow?)

    /**
     * The day's main sleep, its naps and the edits around them, with the sessions shown over [span] for the strip of
     * nights. One read for both, so a delete or a move changes both in the same state. Every sleep belongs to the day
     * it ended, by the times shown ([SleepHistory.nightOf]).
     */
    private fun sleep(date: LocalDate, span: DayWindow, zone: ZoneId): Flow<SleepParts> {
        val ending = SleepHistory.endingOn(date, zone)
        val endingBefore = SleepHistory.endingOn(date.minusDays(1), zone)
        // Sessions ending from the day before into early tomorrow, so the sleeps either side are there to trim against.
        val sleepWindow = DayWindow(endingBefore.from, ending.to + 12 * 3600)
        return health.sleepResolved(DayWindow(minOf(span.from, sleepWindow.from), sleepWindow.to)).flatMapLatest { every ->
            val spanSessions = health.sleepShown(every.filter { it.end in span.from..span.to })
            val all = every.filter { it.end in sleepWindow.from..sleepWindow.to }
            val shown = all.filter { !it.deleted }
            val main = SleepHistory.mainSleep(shown, ending)
            val before = SleepHistory.mainSleep(shown, endingBefore)?.shown
            // With no main sleep, the one the user deleted, for the "No sleep recorded" card and its Restore.
            val deleted = if (main != null) null else SleepHistory.mainSleep(all, ending, deleted = true)
            val resolved = shown.filter { it.strap.nap && it.end in ending.from..ending.to }
            val naps = resolved.mapNotNull { it.shown }
            // Stages stay under the strap's start, trimmed to the times shown.
            // The main sleep's are read whole, so the chart can show what a move left out.
            val strapStages = if (main == null) flowOf(emptyList()) else health.strapStages(main.strap)
            // The strap's apnea summary for the strap's own night, which stays with it when its times move the day.
            val osa = main?.shown?.let { health.osaResultOf(SleepHistory.strapDay(it, zone), zone) } ?: flowOf(null)
            val napStages = if (naps.isEmpty()) flowOf(emptyMap())
            else combine(resolved.map { health.sleepStages(it) }) { st -> naps.zip(st) { n, s -> n.start to s }.toMap() }
            val asDetected = naps.zip(resolved) { n, r -> n.start to r.strap }.toMap()
            combine(strapStages, napStages, osa) { whole, ns, apnea ->
                val st = if (main?.moved == true) SleepEdits.clip(whole, main.start, main.end) else whole
                // A moved main sleep's totals and score come from its clipped stages, as everywhere else ([SleepSource]).
                val session = main?.shown?.let { if (main.moved) SleepEdits.restage(it, main.strap, whole) else it }
                SleepDay(
                    main = session,
                    mainStages = st,
                    naps = naps,
                    napStages = ns,
                    previousScore = before?.score,
                    napsAsDetected = asDetected,
                    mainAsDetected = main?.strap,
                    strapStages = if (main?.moved == true) whole else emptyList(),
                    deletedMain = deleted,
                    neighbours = shown.mapNotNull { it.shown },
                ).let { SleepParts(it, spanSessions, apnea) }
            }
        }
    }

    /** The 7 days ending on [date]: resting heart rate (the strap's, else our own estimate) and HRV. */
    private fun weekly(date: LocalDate, zone: ZoneId): Flow<WeeklyTrends> {
        val first = date.minusDays(6)
        val week = DayWindow(DayWindow.of(first).from, DayWindow.of(date).to)
        return combine(
            health.restingHr(first, date), health.heartRate(week), health.activity(week), health.hrv(week),
        ) { resting, hr, act, hrv ->
            val days = (6 downTo 0).map { date.minusDays(it.toLong()) }
            val restingTrend = days.map { d ->
                val w = DayWindow.of(d, zone)
                val stored = resting.firstOrNull { it.day == d }?.bpm
                // Our own estimate (lowest 30-min average) only means something with most of a day's data.
                val inDay = { t: Long -> t in w.from..w.to }
                val series = Summaries.heartRateSeries(hr.filter { inDay(it.timestamp) }, act.filter { inDay(it.timestamp) })
                val estimated = if (series.size >= MIN_MINUTES_FOR_ESTIMATE) Summaries.estimateRestingHr(series) else null
                DayValue(d, (stored ?: estimated)?.toFloat()) to (stored == null && estimated != null)
            }
            val hrvTrend = days.map { d ->
                val w = DayWindow.of(d, zone)
                val v = hrv.filter { it.timestamp in w.from..w.to }.map { it.milliseconds }
                DayValue(d, if (v.isEmpty()) null else v.average().toFloat())
            }
            WeeklyTrends(restingTrend.map { it.first }, restingTrend.map { it.second }, hrvTrend)
        }
    }

    /** Averages over the 7 days before [date]. */
    private fun baselines(date: LocalDate): Flow<Baselines> {
        val first = date.minusDays(7)
        val last = date.minusDays(1)
        val previous = DayWindow(DayWindow.of(first).from, DayWindow.of(last).to)
        return combine(
            combine(health.restingHr(first, last), health.hrv(previous), health.spo2(previous)) { r, h, s ->
                Triple(r, h, s)
            },
            combine(health.stress(previous), health.skinTemperature(previous), health.breathRate(previous)) { st, sk, br ->
                Triple(st, sk, br)
            },
        ) { (resting, hrv, spo2), (stress, skin, breath) ->
            fun List<Number>.mean() = if (isEmpty()) null else map { it.toFloat() }.average().toFloat()
            Baselines(
                restingHr = resting.map { it.bpm }.mean(),
                hrv = hrv.map { it.milliseconds }.mean(),
                spo2 = spo2.map { it.percent }.mean(),
                stress = stress.map { it.value }.mean(),
                skinTemperature = skin.map { it.celsius }.mean(),
                breathRate = breath.map { it.perMinute }.mean(),
            )
        }
    }

    private class WeeklyTrends(val resting: List<DayValue>, val restingEstimated: List<Boolean>, val hrv: List<DayValue>)

    private companion object {
        /** Minutes of heart-rate data a day needs before we estimate its resting HR ourselves. */
        const val MIN_MINUTES_FOR_ESTIMATE = 6 * 60

        const val CM_PER_M = 100
    }
}
