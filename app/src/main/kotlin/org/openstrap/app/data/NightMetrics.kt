package org.openstrap.app.data

import org.openstrap.app.data.db.NightMetricsRow
import org.openstrap.app.data.db.StrapReadinessRow
import java.time.ZoneId
import kotlin.math.ln

/** OpenStrap's own nightly inputs as logged for the calibration review (evidence-review.md, implementation step 1). */
object NightMetrics {
    /** `hrv_n`: ln of the night's mean gated RMSSD; null without one. Never change this inside a baseline. */
    fun hrvLn(n: Recovery.Night): Float? = n.hrv?.takeIf { it > 0 }?.let { ln(it) }

    /** [n]'s row, under the date its morning ([Recovery.Night.day], a local midnight in [zone]) has there. */
    fun row(n: Recovery.Night, strap: StrapReadinessRow?, now: Long, zone: ZoneId) = NightMetricsRow(
        day = Days.date(n.day, zone),
        updatedAt = now,
        hrvLn = hrvLn(n),
        hrvSamples = n.hrvSamples,
        rhrLowest30 = n.restingHr,
        rhrMeanSleep = n.rhrMeanSleep,
        rhrP10 = n.rhrP10,
        hrMinutes = n.hrMinutes,
        strapHrv = strap?.sleepHrv,
        strapRestingHr = strap?.sleepRestingHr,
    )
}
