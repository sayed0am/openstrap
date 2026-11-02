package org.openstrap.app.data

import kotlinx.coroutines.flow.Flow
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.DailyPai
import org.openstrap.app.data.db.HealthAlertRow
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.OsaEventRow
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepSpo2Sample
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.db.StressSample
import org.openstrap.app.data.settings.UserProfile
import java.time.LocalDate

/**
 * Everything one day shows, read and resolved: which sleep is the main one, the naps, the baselines, the week's trends
 * and the nights. The screens turn it into what they draw, so nothing here knows about charts.
 */
// One function for each thing a day reads or changes, as the screens use them, rather than a few that take a mode.
@Suppress("TooManyFunctions")
interface DayRepository {
    /** The day [date] as it changes. Only [isToday] adds the strap's live step count. */
    fun day(date: LocalDate, isToday: Boolean): Flow<DayData>

    /** Days the calendar lets you pick. */
    fun daysWithData(): Flow<Set<LocalDate>>

    /** Each local day's steps hour by hour, from [from] to [to], keyed by date, read once. */
    suspend fun hourlySteps(from: LocalDate, to: LocalDate): Map<LocalDate, List<Float>>

    /** Readiness and charge for [date], through the cache in [HealthRepository.recovery]. Heavy. */
    suspend fun recovery(date: LocalDate, profile: UserProfile): RecoveryResult

    /** The weekly Healthspan estimate shown on [date], or why it's withheld. Heavy. */
    suspend fun healthspan(date: LocalDate, profile: UserProfile): Healthspan.Outcome

    /** Emits whenever data the scores are worked out from changes. */
    fun changes(): Flow<Unit>

    /** Each day's Heart points from [from] to [to], keyed by local date. */
    fun dailyPai(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DailyPai>>

    /** Each day's stored energy from [from] to [to], keyed by local date. */
    fun dailyEnergy(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DailyEnergyRow>>

    /** Sleep and naps deleted in the last [SleepEdits.KEEP_DAYS], newest first. */
    fun deletedSleep(): Flow<List<SleepEdits.Resolved>>

    /** Keeps [start] to [end] as the times of the strap's sleep or nap [strap]. */
    suspend fun setSleepTimes(strap: SleepSession, start: Long, end: Long)

    /** Deletes the strap's sleep or nap [strap]. [restoreSleep] brings it back. */
    suspend fun deleteSleep(strap: SleepSession)

    /** Brings back the strap's deleted sleep or nap [strap], as it was. */
    suspend fun restoreSleep(strap: SleepSession)
}

/** One day's value of a weekly trend, null where the day has no data. */
data class DayValue(val date: LocalDate, val value: Float?)

/** Averages over the 7 days before the selected day, null where there's no data. */
data class Baselines(
    val restingHr: Float? = null,
    val hrv: Float? = null,
    val spo2: Float? = null,
    val stress: Float? = null,
    val skinTemperature: Float? = null,
    val breathRate: Float? = null,
)

/** One night's main sleep and the vitals averaged over it. */
data class SleepNight(
    val date: LocalDate,
    val session: SleepSession?,
    val hrv: Float? = null,
    val skinTemperature: Float? = null,
    val spo2: Float? = null,
    val breathing: Float? = null,
) {
    val asleepSeconds: Long? get() = session?.let(SleepHistory::asleepSeconds)
}

/** A day's sleep, resolved: the night's main sleep ([SleepHistory.mainSleep]), the day's naps and the edits around them. */
data class SleepDay(
    /** The main sleep as shown, carrying the strap's own score and stage totals when it reported them. */
    val main: SleepSession? = null,
    /** The night's stages, clipped to [main]'s times. */
    val mainStages: List<SleepStage> = emptyList(),
    val naps: List<SleepSession> = emptyList(),
    /** Each nap's stages, keyed by the nap's start as shown. */
    val napStages: Map<Long, List<SleepStage>> = emptyMap(),
    /** The strap's score for the night before. */
    val previousScore: Int? = null,
    /** Each nap as the strap saw it, keyed by the nap's start as shown. */
    val napsAsDetected: Map<Long, SleepSession> = emptyMap(),
    /** The main sleep as the strap saw it. */
    val mainAsDetected: SleepSession? = null,
    /** The strap's whole stages for a main sleep the user moved, else empty. */
    val strapStages: List<SleepStage> = emptyList(),
    /** The night's main sleep the user deleted, when there's no other ([main] is null then), for its Restore. */
    val deletedMain: SleepEdits.Resolved? = null,
    /** Every sleep and nap as shown around the day, from the night before to tonight. */
    val neighbours: List<SleepSession> = emptyList(),
)

/** One day read and resolved by a [DayRepository]. Rows are the database's own, so the screens choose how to draw them. */
data class DayData(
    val date: LocalDate,
    val isToday: Boolean = false,
    val day: DayWindow = DayWindow.of(date),
    val night: DayWindow = DayWindow.nightEndingOn(date),
    /** The day's activity minutes, for the steps by hour and the heart rate series. */
    val minutes: List<ActivityMinute> = emptyList(),
    val heartRate: List<HeartRateSample> = emptyList(),
    /** Steps shown: today, the strap's live count when it's ahead of the synced minutes ([LiveSteps]). */
    val steps: Int = 0,
    /** The 7 days ending on [date], oldest first: the strap's resting heart rate, else our own estimate. */
    val restingTrend: List<DayValue> = emptyList(),
    /** For each of [restingTrend], whether it's our own estimate. */
    val restingEstimated: List<Boolean> = emptyList(),
    val hrvTrend: List<DayValue> = emptyList(),
    val sleep: SleepDay = SleepDay(),
    /** The [SleepHistory.NIGHTS] nights ending on [date], oldest first. */
    val nights: List<SleepNight> = emptyList(),
    /** Heart rate across the night window. */
    val nightHeartRate: List<HeartRateSample> = emptyList(),
    /** HRV and skin temperature read during the night's main sleep. */
    val sleepHrv: List<HrvSample> = emptyList(),
    val sleepSkin: List<SkinTemperature> = emptyList(),
    val spo2: List<Spo2Sample> = emptyList(),
    val stress: List<StressSample> = emptyList(),
    val hrv: List<HrvSample> = emptyList(),
    val skin: List<SkinTemperature> = emptyList(),
    val breath: List<BreathRate> = emptyList(),
    val pai: PaiSample? = null,
    val baselines: Baselines = Baselines(),
    val sleepSpo2: List<SleepSpo2Sample> = emptyList(),
    val hrvSdnn: List<HrvSdnnSample> = emptyList(),
    val osaResult: OsaResultRow? = null,
    val osaEvents: List<OsaEventRow> = emptyList(),
    val dailyMaxHeartRate: Int? = null,
    val distanceMeters: Int? = null,
    val activeCalories: Int? = null,
    val alerts: List<HealthAlertRow> = emptyList(),
)
