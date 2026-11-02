package org.openstrap.app.presentation

import org.openstrap.app.data.Recovery

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
    val WORDS = listOf("Take it easy", "Moderate", "Ready", "Ready for more")

    /** Which band [score] is in, 0 (lowest) to 3. */
    fun bar(score: Int): Int = FROM.count { score >= it }

    private val FROM = listOf(MODERATE_FROM, READY_FROM, READY_FOR_MORE_FROM)

    /** The status word beside the score. An [alert] (signals off your normal) always reads "Take it easy". */
    fun word(score: Int, alert: Boolean = false): String = if (alert) WORDS[0] else WORDS[bar(score)]
}

/** The sleep score's bands, the ones the strap's score is built for: 90 "Great", 80 "Good", 60 "Fair", else "Poor". */
object SleepBands {
    const val GREAT_FROM = 90
    const val GOOD_FROM = 80
    const val FAIR_FROM = 60

    /** The words low to high, one per bar of the scale. */
    val WORDS = listOf("Poor", "Fair", "Good", "Great")

    /** Which band [score] is in, 0 (lowest) to 3. */
    fun bar(score: Int): Int = FROM.count { score >= it }

    private val FROM = listOf(FAIR_FROM, GOOD_FROM, GREAT_FROM)

    fun word(score: Int): String = WORDS[bar(score)]
}

/** How a day's Load compares with your usual. */
enum class LoadBand(val text: String) {
    Above("Above your usual"),
    Usual("Usual"),
    Below("Below your usual"),

    /** Before the evening, a Load not above your usual is only so far: against a whole usual day it would always look low. */
    SoFar("So far today"),
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
