package org.openstrap.app.ui.dashboard

import java.time.LocalDate
import org.openstrap.app.R
import org.openstrap.app.data.EnergyExpenditure
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.RecoveryResult
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.i18n.Strings
import org.openstrap.app.presentation.Units

/*
 * Data states (spec 4.3, patterns E3 and E4): pairing as one stage, first nights, no sleep last night and a partial
 * day. Only what the screens say and show changes; every rule here is a plain function, so each boundary is tested.
 * The text is in res/values/strings_day_today.xml.
 */

/* --------------------------------------------- Pairing --------------------------------------------- */

/** A step of the pairing stage on Today (E3): the strap stays still, only the line to the phone and the sentence change. */
enum class PairStep { Ready, Searching, Found, Connecting, Connected, Failed, KeyRejected }

/**
 * The pairing step from what the app already knows: before a strap is [paired], the Strap page's scan ([scanning],
 * a strap [found]); once paired, the connection.
 */
fun pairStep(paired: Boolean, scanning: Boolean, found: Boolean, connection: ConnectionState): PairStep = when {
    !paired -> when {
        found -> PairStep.Found
        scanning -> PairStep.Searching
        else -> PairStep.Ready
    }
    connection is ConnectionState.Connected -> PairStep.Connected
    connection is ConnectionState.Failed -> if (connection.keyRejected) PairStep.KeyRejected else PairStep.Failed
    // Just paired, the link is being made: a disconnected moment before it starts counts as connecting.
    else -> PairStep.Connecting
}

/** The pairing stage's sentence for [step]. */
fun pairSentence(step: PairStep, strings: Strings = Strings.app): String = strings.get(
    when (step) {
        PairStep.Ready -> R.string.day_pair_ready
        PairStep.Searching -> R.string.day_pair_searching
        PairStep.Found -> R.string.day_pair_found
        PairStep.Connecting -> R.string.day_pair_connecting
        PairStep.Connected -> R.string.day_pair_connected
        PairStep.Failed -> R.string.day_pair_failed
        PairStep.KeyRejected -> R.string.day_pair_key_rejected
    },
)

/** How long Today keeps the stage on Connected, the line solid, before the day's own hero takes over. */
const val PAIRED_HOLD_MS = 3_000L

/* ------------------------------------------ No sleep, first ------------------------------------------ */

/** The tag on the Recovery hero the day the first real score shows (E4). */
val FIRST_SCORE: String get() = Strings.app.get(R.string.day_first_score)

/** The day remembered for scores from before the app kept a first score, so the marker never shows for them. */
val BEFORE_FIRST_SCORE: LocalDate = LocalDate.of(1970, 1, 1)

/** Whether the Recovery hero marks [date]'s score as the first (E4), and the day to remember for it, if any. */
data class FirstScore(val show: Boolean, val remember: LocalDate? = null)

/**
 * The first real score is marked once (E4): all of its day, never again. [seen] is the first score's day as stored,
 * null before one. With none stored, today's score is the first when Recovery is still in its first scored phase
 * ([learning]) and no score came before it in the week ([earlier], the earliest day before [date] with one). Another
 * score is remembered as it is, so the marker never shows for it. Only today's score is decided; a past day shows
 * what was stored.
 */
fun firstScore(score: Int?, date: LocalDate, isToday: Boolean, learning: Boolean, earlier: LocalDate?, seen: LocalDate?): FirstScore = when {
    seen != null -> FirstScore(show = score != null && seen == date)
    score == null || !isToday -> FirstScore(false)
    earlier != null -> FirstScore(false, remember = earlier)
    !learning -> FirstScore(false, remember = BEFORE_FIRST_SCORE)
    else -> FirstScore(true, remember = date)
}

/** The earliest day before [date] in [trend] with a score, for [firstScore]. */
fun earliestScore(trend: List<Pair<LocalDate, Int?>>, date: LocalDate): LocalDate? =
    trend.filter { it.first < date && it.second != null }.minOfOrNull { it.first }

/* -------------------------------------------- Partial day -------------------------------------------- */

/**
 * The one partial day rule: a day counts as partial when the strap was worn for less than this share of it. 20 h of
 * 24, the full day the calories estimate already uses ([EnergyExpenditure.FULL_DAY_MINUTES]). For today the share is of
 * the day so far, up to the newest reading, so a sync that hasn't run yet doesn't count as time off the wrist.
 */
const val PARTIAL_WEAR_SHARE = EnergyExpenditure.FULL_DAY_MINUTES / (24 * 60f)

/** The heart rate series' buckets ([DayUiState.heartRate]): one with a reading counts as this long worn. */
const val WEAR_BUCKET_MINUTES = 5

/** How much of the day the strap was worn: [wornMinutes] of [ofMinutes] (the whole day, or today so far). */
data class Wear(val wornMinutes: Int, val ofMinutes: Int) {
    val partial: Boolean get() = wornMinutes < ofMinutes * PARTIAL_WEAR_SHARE
}

/**
 * Wear from what the app already has: the calories estimate's minutes with data ([caloriesMinutes]: steps, heart rate or
 * sleep) when it ran, which needs a profile, else the heart rate buckets with a reading ([hrBuckets] bucket starts).
 * Out of [dayStart]..[dayEnd], or today up to the [newest] reading. Null with no wear at all: an empty day is waiting
 * for data, not partial.
 */
fun wear(caloriesMinutes: Int?, hrBuckets: List<Long>, dayStart: Long, dayEnd: Long, isToday: Boolean, newest: Long?): Wear? {
    val inDay = hrBuckets.filter { it in dayStart..dayEnd }
    val worn = caloriesMinutes ?: (inDay.distinct().size * WEAR_BUCKET_MINUTES)
    if (worn <= 0) return null
    val dayMinutes = ((dayEnd - dayStart + 1) / 60).toInt()
    val until = if (isToday) (newest ?: inDay.maxOrNull()?.plus(WEAR_BUCKET_MINUTES * 60L)) ?: return null else dayEnd + 1
    val of = ((until - dayStart) / 60).toInt().coerceIn(1, dayMinutes)
    return Wear(worn.coerceAtMost(of), of)
}

/** The day's [Wear] from its state and Recovery (for the calories estimate's minutes). */
fun DayUiState.wear(recovery: RecoveryResult?, isToday: Boolean): Wear? {
    val calories = recovery?.takeIf { it.date == date }?.calories?.minutesWithData
    val buckets = heartRate.map { it.x }
    return wear(calories, buckets, dayStart, dayEnd, isToday, buckets.maxOrNull()?.plus(WEAR_BUCKET_MINUTES * 60L))
}

/** The marker next to the title of a card whose figures a partial day lowers. */
val PARTIAL_DAY: String get() = Strings.app.get(R.string.day_partial_day)

/** The partial day sheet's line: how much wear the figures cover. */
fun partialLine(w: Wear, isToday: Boolean, strings: Strings = Strings.app): String {
    val worn = Units.duration(w.wornMinutes * 60L)
    return if (isToday) strings.format(R.string.day_partial_line_today, worn, Units.duration(w.ofMinutes * 60L))
    else strings.format(R.string.day_partial_line, worn)
}

/** The partial day sheet (spec 6.2, with no scale): what it means. [partialLine] and [PARTIAL_DAY_ROWS] go under it. */
val PARTIAL_DAY_INFO: Info get() = Info(
    name = PARTIAL_DAY,
    meaning = Strings.app.get(R.string.day_partial_meaning),
    scale = emptyList(), how = "", moves = "", off = "",
)

/** The sheet's folded rows: what a partial day lowers, and when a day counts as one. */
val PARTIAL_DAY_ROWS: List<Pair<String, String>> get() = Strings.app.let { strings ->
    listOf(
        strings.get(R.string.day_partial_lowers_title) to strings.get(R.string.day_partial_lowers),
        strings.get(R.string.day_partial_when_title) to
            strings.format(R.string.day_partial_when, Units.duration(EnergyExpenditure.FULL_DAY_MINUTES * 60L)),
    )
}

/* ------------------------------------------ Debug previews ------------------------------------------ */

/**
 * Debug only: a data state to show on a phone whose data is fine, from the `data_state` extra. Like [StrapPreview]
 * it changes what the screens are shown and nothing else: no Bluetooth, no scan, no sync, nothing written.
 */
enum class DataPreview(val extra: String, val pair: PairStep? = null) {
    PairReady("pair", PairStep.Ready),
    PairSearch("pair_search", PairStep.Searching),
    PairFound("pair_found", PairStep.Found),
    PairConnecting("pair_connecting", PairStep.Connecting),
    PairConnected("pair_connected", PairStep.Connected),
    PairFailed("pair_failed", PairStep.Failed),
    FirstNights("firstnights"),
    NoSleep("nosleep"),
    Partial("partial"),
    FirstScoreShown("firstscore");

    /** Before a strap is paired the steps show with no strap, as the real ones do. */
    val unpaired: Boolean get() = pair == PairStep.Ready || pair == PairStep.Searching || pair == PairStep.Found

    companion object {
        fun of(extra: String): DataPreview? = entries.firstOrNull { it.extra.equals(extra, ignoreCase = true) }
    }
}

/** This day as the [preview] shows it: no night for first nights and no sleep, the real day otherwise. */
fun DayUiState.previewed(preview: DataPreview?): DayUiState = when (preview) {
    DataPreview.FirstNights -> withoutNight().copy(nights = nights.map { it.copy(session = null) }, baselines = Baselines())
    DataPreview.NoSleep -> withoutNight().copy(nights = nights.map { if (it.date == date) it.copy(session = null) else it })
    else -> this
}

private fun DayUiState.withoutNight() = copy(
    sleep = null, previousSleepScore = null, sleepStages = emptyList(), sleepBreakdown = null, sleepSpo2 = emptyList(),
    osaResult = null, osaEvents = emptyList(), sleepHeartRate = emptyList(), sleepHrv = emptyList(), sleepSkinTemperature = emptyList(),
)

/**
 * This Recovery as the [preview] shows it. First nights: no score, Charge not yet anchored. No sleep: no score today,
 * Charge restarted from an estimate as it is with the strap off overnight. The real one otherwise.
 */
fun RecoveryResult.previewed(preview: DataPreview?): RecoveryResult = when (preview) {
    DataPreview.FirstNights -> copy(
        readiness = readiness.copy(score = null, contributors = emptyList(), baselineNights = 0, phase = Recovery.Phase.Withheld, strainAlert = false, withheld = false),
        charge = charge.copy(anchored = false, atWake = null, strapOffOvernight = false),
        readinessTrend = readinessTrend.map { it.first to null }, tonight = null, breathingDisturbances = null, regularity = null,
    )
    DataPreview.NoSleep -> copy(
        readiness = readiness.copy(score = null, contributors = emptyList(), strainAlert = false),
        charge = charge.copy(strapOffOvernight = true),
        readinessTrend = readinessTrend.map { if (it.first == date) it.first to null else it }, tonight = null, breathingDisturbances = null,
    )
    else -> this
}

/** The [wear] the [preview] shows: about half the day worn, so the partial day marker and its sheet can be seen. */
fun Wear?.previewed(preview: DataPreview?, ofMinutes: Int): Wear? =
    if (preview == DataPreview.Partial) Wear((this?.ofMinutes ?: ofMinutes) * 55 / 100, this?.ofMinutes ?: ofMinutes) else this
