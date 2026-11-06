package org.openstrap.app.ui.dashboard

import org.openstrap.app.data.DayStat
import org.openstrap.app.data.Healthspan
import org.openstrap.app.data.History
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.LoadBand
import org.openstrap.app.presentation.LoadBands
import java.time.Instant
import java.time.ZoneId

/*
 * One off status per kind of value (spec 2.1 and 2.5): where a value sits against your normal or usual, as an
 * [OffStatus] the rim and the off tag ([offTag]) read. Every function here is pure, so the rules are tested without a
 * screen, and each says Usual when either side is missing: an unknown value is never news.
 */

/** [value] against [normal] ([History.status]): Above over its high, Below under its low, else Usual. */
fun offStatus(value: Float?, normal: History.Normal?): OffStatus = OffStatus.of(History.status(value, normal) ?: 0)

/** A body signal (resting heart rate, HRV, heart rate, blood oxygen, breathing rate, skin temperature) against your normal. */
fun bodyOffStatus(value: Float?, normal: History.Normal?): OffStatus = offStatus(value, normal)

/**
 * Today's [load] against your [usual] Load ([LoadBands.of]), Above and Below both. Before the evening ([endOfDay]
 * false) a low Load is only so far, so it stays Usual.
 */
fun loadOffStatus(load: Float?, usual: Float?, endOfDay: Boolean = true): OffStatus =
    when (load?.let { LoadBands.of(it, usual, endOfDay) }) {
        LoadBand.Above -> OffStatus.Above
        LoadBand.Below -> OffStatus.Below
        LoadBand.Usual, LoadBand.SoFar, null -> OffStatus.Usual
    }

/** The day's Recovery score against its usual ([RecoveryResult.readinessUsual]). */
fun recoveryOffStatus(r: RecoveryResult?): OffStatus = offStatus(r?.readiness?.score?.toFloat(), r?.readinessUsual)

/**
 * The usual length of a night: mean ± 1 SD of the time asleep ([SleepNight.asleepSeconds]) of the [nights] ending
 * on [date] with a sleep, in seconds ([History.normal]). Null under [History.MIN_NORMAL_DAYS] such nights.
 */
fun sleepLengthNormal(nights: List<SleepNight>, date: java.time.LocalDate): History.Normal? = History.normal(
    nights.mapNotNull { n -> n.asleepSeconds?.toFloat()?.let { DayStat(n.date, it, it, it, 1) } }, date,
)

/** Last night's length against [DayUiState.sleepLengthNormal]: Above is Long, Below is Short. */
fun sleepLengthOffStatus(state: DayUiState): OffStatus = offStatus(state.asleepSeconds?.toFloat(), state.sleepLengthNormal)

/** Minutes after noon of [epochSeconds] in [zone], so a bedtime either side of midnight never wraps. */
fun minutesAfterNoon(epochSeconds: Long, zone: ZoneId): Int {
    val t = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime()
    return Math.floorMod(t.hour * 60 + t.minute - 12 * 60, 24 * 60)
}

/** A bedtime's usual band is never narrower than this either side: a few minutes later isn't news. */
const val BEDTIME_MIN_SPREAD_MINUTES = 30f

/**
 * Your usual bedtime as a band, in minutes after noon ([minutesAfterNoon]): mean ± 1 SD of the main sleeps' starts of
 * the [nights] ending on [date] ([History.normal]), at least [BEDTIME_MIN_SPREAD_MINUTES] either side. Null under
 * [History.MIN_NORMAL_DAYS] nights with a sleep.
 */
fun bedtimeNormal(nights: List<SleepNight>, date: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault()): History.Normal? {
    val n = History.normal(
        nights.mapNotNull { night ->
            night.session?.let { minutesAfterNoon(it.start, zone).toFloat() }?.let { DayStat(night.date, it, it, it, 1) }
        },
        date,
    ) ?: return null
    val spread = maxOf(n.high - n.mean, BEDTIME_MIN_SPREAD_MINUTES)
    return n.copy(low = n.mean - spread, high = n.mean + spread)
}

/** Last night's bedtime against [DayUiState.bedtimeNormal]: Above is Later, Below is Earlier. */
fun bedtimeOffStatus(state: DayUiState, zone: ZoneId = ZoneId.systemDefault()): OffStatus =
    offStatus(state.sleep?.let { minutesAfterNoon(it.start, zone).toFloat() }, state.bedtimeNormal)

/** Last night's breathing disturbances against their usual band ([RecoveryResult.disturbancesUsual]). Only Above is news. */
fun breathingOffStatus(r: RecoveryResult?): OffStatus = offStatus(r?.breathingDisturbances?.perHour, r?.disturbancesUsual)

/**
 * Habit Age against your own age: Above (Older) when even the low end of the estimate's range is past your age,
 * Below (Younger) when even its high end is under it, else Usual. Usual while the estimate is withheld.
 */
fun habitAgeOffStatus(outcome: Healthspan.Outcome?): OffStatus {
    val r = outcome?.result ?: return OffStatus.Usual
    return when {
        r.low > r.age -> OffStatus.Above
        r.high < r.age -> OffStatus.Below
        else -> OffStatus.Usual
    }
}

/** The day's mean stress from its [samples], null without any: the value Stress' daily history keeps. */
fun stressMean(samples: List<ChartPoint>): Float? = samples.takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.toFloat()

/** The day's stress ([stressMean] of [samples]) against your normal (`rememberNormal(Metric.Stress)`). */
fun stressOffStatus(samples: List<ChartPoint>, normal: History.Normal?): OffStatus = offStatus(stressMean(samples), normal)
