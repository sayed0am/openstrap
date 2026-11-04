package org.openstrap.app.presentation

import androidx.annotation.StringRes
import org.openstrap.app.R
import org.openstrap.app.data.Recovery
import org.openstrap.app.i18n.Strings

/*
 * Where a score or reading falls, defined once (decisions 8 and 10 in Units). The values and words are the ones the
 * screens wrote out by hand before.
 */

/** Recovery's bands: 85 and up "Ready for more", 70 "Ready", from [Recovery.FAIR_FROM] "Moderate", else "Take it easy". */
object RecoveryBands {
    const val READY_FOR_MORE_FROM = 85
    const val READY_FROM = 70
    const val MODERATE_FROM = Recovery.FAIR_FROM

    /** The words low to high, one per bar of the scale. */
    val WORDS: List<String>
        get() = listOf(
            R.string.core_band_recovery_easy, R.string.core_band_recovery_moderate, R.string.core_band_recovery_ready,
            R.string.core_band_recovery_more,
        ).map(Strings.app::get)

    /** Which band [score] is in, 0 (lowest) to 3. */
    fun bar(score: Int): Int = FROM.count { score >= it }

    private val FROM = listOf(MODERATE_FROM, READY_FROM, READY_FOR_MORE_FROM)

    /** The status word beside the score. An [alert] (signals off your normal) always reads "Take it easy". */
    fun word(score: Int, alert: Boolean = false): String = WORDS[if (alert) 0 else bar(score)]
}

/** The sleep score's bands, the ones the strap's score is built for: 90 "Great", 80 "Good", 60 "Fair", else "Poor". */
object SleepBands {
    const val GREAT_FROM = 90
    const val GOOD_FROM = 80
    const val FAIR_FROM = 60

    /** The words low to high, one per bar of the scale. */
    val WORDS: List<String>
        get() = listOf(
            R.string.core_band_sleep_poor, R.string.core_band_sleep_fair, R.string.core_band_sleep_good, R.string.core_band_sleep_great,
        ).map(Strings.app::get)

    /** Which band [score] is in, 0 (lowest) to 3. */
    fun bar(score: Int): Int = FROM.count { score >= it }

    private val FROM = listOf(FAIR_FROM, GOOD_FROM, GREAT_FROM)

    fun word(score: Int): String = WORDS[bar(score)]
}

/** How a day's Load compares with your usual. */
enum class LoadBand(@StringRes private val textId: Int) {
    Above(R.string.core_band_load_above),
    Usual(R.string.core_band_load_usual),
    Below(R.string.core_band_load_below),

    /** Before the evening, a Load not above your usual is only so far: against a whole usual day it would always look low. */
    SoFar(R.string.core_band_load_so_far),
    ;

    val text: String get() = Strings.app.get(textId)
}

/** Today's Load rule: within [BAND] of your usual either way is usual. */
object LoadBands {
    /** A Load within this share of your usual counts as usual. */
    const val BAND = 0.25f

    /** [load] against [usual]. [endOfDay] false keeps a low Load at [LoadBand.SoFar]. Null without a usual. */
    fun of(load: Float, usual: Float?, endOfDay: Boolean = true): LoadBand? {
        if (usual == null || usual <= 0f) return null
        return when {
            load > usual * (1 + BAND) -> LoadBand.Above
            !endOfDay -> LoadBand.SoFar
            load < usual * (1 - BAND) -> LoadBand.Below
            else -> LoadBand.Usual
        }
    }

    /** Your usual as a range, the band a Load counts as usual in, null without a usual. */
    fun usualRange(usual: Float?): ClosedFloatingPointRange<Float>? = usual?.takeIf { it > 0f }?.let { it * (1 - BAND)..it * (1 + BAND) }
}

/** When the strap needs a word: its data out of date, its battery low. */
object StrapBands {
    /**
     * A last sync older than this is out of date. Connected, the strap syncs at least hourly, so 3 hours is two missed
     * syncs or more: one alone happens in normal wear.
     */
    const val STALE_AFTER_S = 3 * 3600L

    /** At or under this battery level, while not charging, the strap's battery is low. */
    const val LOW_BATTERY = 15

    /** Whether a sync at [lastSync] is out of date at [now], both epoch seconds: more than [STALE_AFTER_S] before. */
    fun stale(lastSync: Long?, now: Long): Boolean = lastSync != null && now - lastSync > STALE_AFTER_S

    /** Whether [percent] is low: at or under [LOW_BATTERY] and not [charging]. */
    fun lowBattery(percent: Int?, charging: Boolean): Boolean = percent != null && !charging && percent <= LOW_BATTERY
}

/**
 * Charge as a whole number the way every screen, widget and the notification show it, cut rather than rounded, so they
 * all agree.
 */
fun chargeShown(value: Float): Int = value.toInt()
