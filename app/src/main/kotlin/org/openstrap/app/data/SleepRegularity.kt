package org.openstrap.app.data

import org.openstrap.app.data.db.SleepStage
import kotlin.math.abs
import kotlin.math.sqrt
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Sleep regularity and the sleep-health count (docs/algorithms/evidence-review.md, "Regularity" and "Sleep-health
 * count"). The Sleep Regularity Index predicted all-cause mortality better than duration in 60,977 UK Biobank
 * participants, and a valid SRI needed only ≥ 120 h of paired epochs (Windred et al. 2024). A count of good sleep
 * dimensions predicted cardiometabolic morbidity where most single dimensions didn't (Brindle et al. 2019). Shown on
 * the Sleep screen, never in Readiness.
 */
object SleepRegularity {
    /** One-minute epochs per grid day; grid days start at [DAY_START_HOUR] so a night isn't split at midnight. */
    const val EPOCHS = 1440
    const val DAY_START_HOUR = 12
    /** Day-pairs compared, so [SRI_DAYS] + 1 grid days. */
    const val SRI_DAYS = 7
    /** Withhold below 7200 valid pairs (120 h). */
    const val MIN_VALID_PAIRS = 7200
    /** Fallback when the SRI is withheld: SD of the main-sleep midpoint over the last 7 days, from ≥ 4 sleeps. */
    const val MIDPOINT_MIN_SLEEPS = 4
    const val MIDPOINT_SD_GOOD_MINUTES = 60f

    const val SLEEP: Byte = 1
    const val WAKE: Byte = 0
    const val MISSING: Byte = -1

    /**
     * Descriptive bands at the UK Biobank SRI quintile cuts (Windred et al. 2024, n = 60,977, wrist accelerometry
     * scored with GGIR): 71.65 / 78.56 / 83.17 / 87.32, median 81.0, rounded to 72 / 79 / 87. Irregular is the bottom fifth, where most of the
     * mortality risk sat (Q2 vs Q1 HR 0.80); Fair the second fifth; Regular the third and fourth fifths; Very
     * regular the top fifth. They place you in that population, nothing more: the strap's sleep/wake minutes are
     * not GGIR's, so treat a point or two either side of a cut as the same. Zepp's 60/80/90 bands belong to its own
     * night-overlap score, a different scale, and are not used.
     */
    enum class Band(val label: String) { Irregular(tr(R.string.sleep_regularity_irregular)), Fair(tr(R.string.common_fair)), Regular(tr(R.string.sleep_regularity_regular)), VeryRegular(tr(R.string.sleep_regularity_very_regular)) }

    fun band(sri: Float): Band = when {
        sri < 72f -> Band.Irregular
        sri < 79f -> Band.Fair
        sri < 87f -> Band.Regular
        else -> Band.VeryRegular
    }

    data class Result(
        /**
         * The Sleep Regularity Index on its standard scale (Phillips et al. 2017; as GGIR and Windred et al. 2024
         * report it): −100 to 100, where 100 is the same sleep/wake state at every minute 24 h apart and 0 is what
         * random sleep gives. In UK Biobank the middle half sat between 73.8 and 86.3. Null when withheld (fewer than
         * [MIN_VALID_PAIRS] valid pairs). This is the "Regularity" number the Sleep screen shows.
         */
        val sri: Float?,
        val validPairs: Int,
        /** SD of the main-sleep midpoint, minutes, when the SRI is withheld and there are enough sleeps. */
        val midpointSdMinutes: Float?,
    ) {
        val band get() = sri?.let(::band)
        /** Regular enough for the sleep-health count: SRI ≥ 72, or the midpoint fallback under an hour. */
        val good: Boolean? get() = sri?.let { it >= 72f } ?: midpointSdMinutes?.let { it < MIDPOINT_SD_GOOD_MINUTES }
    }

    /**
     * One grid day from [from] (a local noon) over [EPOCHS] minutes: [SLEEP] for any deep, light or REM minute (naps
     * included), [WAKE] for an awake stage or a minute the strap was worn ([worn]), [MISSING] otherwise.
     */
    fun grid(from: Long, stages: List<Triple<Long, Long, Int>>, worn: (Long) -> Boolean): ByteArray {
        val g = ByteArray(EPOCHS) { i -> if (worn(from + i * 60L)) WAKE else MISSING }
        for ((start, end, stage) in stages) {
            val a = ((start - from) / 60).toInt().coerceAtLeast(0)
            val b = ((end - from + 59) / 60).toInt().coerceAtMost(EPOCHS)
            for (i in a until b) {
                // A sleep minute wins over an awake one when stages touch.
                if (stage == SleepStage.AWAKE) { if (g[i] != SLEEP) g[i] = WAKE } else g[i] = SLEEP
            }
        }
        return g
    }

    /**
     * SRI over consecutive [grids] (oldest first): −100 + 200·matches/validPairs, counting only minute pairs where both
     * days are valid (as GGIR does). Withheld below [MIN_VALID_PAIRS].
     */
    fun sri(grids: List<ByteArray>): Pair<Float?, Int> {
        var valid = 0
        var matches = 0
        for (d in 0 until grids.size - 1) {
            val a = grids[d]
            val b = grids[d + 1]
            for (i in 0 until EPOCHS) {
                if (a[i] == MISSING || b[i] == MISSING) continue
                valid++
                if (a[i] == b[i]) matches++
            }
        }
        val sri = if (valid < MIN_VALID_PAIRS) null else -100f + 200f * matches / valid
        return sri to valid
    }

    /** SD of sleep midpoints given as minutes from [DAY_START_HOUR] (so a night never wraps); null under [MIDPOINT_MIN_SLEEPS]. */
    fun midpointSd(midpoints: List<Float>): Float? {
        if (midpoints.size < MIDPOINT_MIN_SLEEPS) return null
        val mean = midpoints.average()
        return sqrt(midpoints.sumOf { (it - mean) * (it - mean) } / (midpoints.size - 1)).toFloat()
    }

    fun result(grids: List<ByteArray>, midpoints: List<Float>): Result {
        val (sri, valid) = sri(grids)
        return Result(sri, valid, if (sri == null) midpointSd(midpoints) else null)
    }

    // ------------------------------------------------------------------------------------------------
    // Sleep-health count
    // ------------------------------------------------------------------------------------------------

    /** Good duration: 420-540 min (≥ 65: up to 510). */
    const val DURATION_GOOD_MIN = 420
    const val DURATION_GOOD_MAX = 540
    const val DURATION_GOOD_MAX_65 = 510
    /** Good continuity: WASO ≤ 30 min, or sleep-period efficiency ≥ 90 %. */
    const val WASO_GOOD_MINUTES = 30
    const val SLEEP_PERIOD_EFFICIENCY_GOOD = 0.90f
    /** Timing is informational: the midpoint is flagged outside 01:30-04:30, never scored. */
    const val MIDPOINT_EARLIEST = 90
    const val MIDPOINT_LATEST = 270

    data class Health(
        /** 0-3: duration, continuity and regularity that are good. */
        val count: Int,
        val duration: Boolean,
        /** Null when the night had no stages to tell. */
        val continuity: Boolean?,
        /** Null when there's neither an SRI nor the midpoint fallback. */
        val regularity: Boolean?,
        /** Main-sleep midpoint, minutes after local midnight (0-1439). */
        val midpointMinutes: Int,
        val midpointOutside: Boolean,
    )

    /**
     * The count for one night: [asleepMinutes] (TST), [wasoMinutes] and the sleep period ([onset] to [finalWake], epoch
     * seconds) for efficiency, [regularity] from [result], and the midpoint as local minutes after midnight.
     */
    fun health(asleepMinutes: Int, wasoMinutes: Int?, sleepPeriodMinutes: Int?, regularity: Result?, age: Int?, midpointMinutes: Int): Health {
        val max = if (age != null && age >= 65) DURATION_GOOD_MAX_65 else DURATION_GOOD_MAX
        val duration = asleepMinutes in DURATION_GOOD_MIN..max
        val efficiency = sleepPeriodMinutes?.takeIf { it > 0 }?.let { asleepMinutes.toFloat() / it }
        val continuity = when {
            wasoMinutes != null && wasoMinutes <= WASO_GOOD_MINUTES -> true
            efficiency != null && efficiency >= SLEEP_PERIOD_EFFICIENCY_GOOD -> true
            wasoMinutes == null && efficiency == null -> null
            else -> false
        }
        val regular = regularity?.good
        val count = listOf(duration, continuity == true, regular == true).count { it }
        val m = ((midpointMinutes % EPOCHS) + EPOCHS) % EPOCHS
        return Health(count, duration, continuity, regular, m, m !in MIDPOINT_EARLIEST..MIDPOINT_LATEST)
    }

    /** "03:12". */
    fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, abs(minutes % 60))
}
