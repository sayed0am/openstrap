package org.openstrap.app.data

import org.openstrap.app.data.settings.UserProfile
import kotlin.math.exp
import kotlin.math.ln
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * The strap's PAI ring maths (native 08 §3.4, §3.7), so the app can say what leaves the 7-day total tomorrow.
 * A day's stored `daily` is its marginal PAI against the older days of *its own* week; on a saturating curve with a
 * 75/day cap that isn't what dropping it removes (native 08 §7 point 1).
 */
object Pai {
    /** One day can add at most this much PAI (native 08 §3.4). */
    const val DAILY_CAP = 75.0

    /** `PAI(S) = A/B · (1 − e^(−S))`; set 0 is female, set 1 male (native 08 §2.4, an inference). */
    private class Curve(val a: Double, val b: Double)
    private val FEMALE = Curve(983.81995, 3.0625992)
    private val MALE = Curve(975.80994, 1.7675018)

    /** Unknown sex falls back to set 0, as Zepp does for a missing gender (native 08 §2.4). */
    private fun curve(sex: UserProfile.Sex?) = if (sex == UserProfile.Sex.Male) MALE else FEMALE

    /** PAI from a summed activity score. */
    fun pai(score: Double, sex: UserProfile.Sex?): Double = curve(sex).let { it.a * (1 - exp(-score)) / it.b }

    /** The inverse, `S = −ln(1 − B·PAI/A)`, held just under the ceiling so it stays finite. */
    fun score(pai: Double, sex: UserProfile.Sex?): Double {
        val c = curve(sex)
        return -ln((1 - c.b * pai.coerceAtLeast(0.0) / c.a).coerceAtLeast(1e-9))
    }

    /** Total PAI of ring slots, oldest first, each day adding at most [DAILY_CAP] (native 08 §3.4). */
    fun total(slots: List<Double>, sex: UserProfile.Sex?): Double {
        var s = 0.0
        for (slot in slots) {
            val before = pai(s, sex)
            s = if (pai(s + slot, sex) - before <= DAILY_CAP) s + slot else score(before + DAILY_CAP, sex)
        }
        return pai(s, sex)
    }

    /**
     * A day's slot rebuilt from its last record: `total − daily` is the PAI of the six older days (native 08 §3.4),
     * so the slot is the score between that and `total`. Exact for an uncapped day. For a capped day it's the least
     * score that reached the cap; that's enough, because once older days leave, the day still caps.
     */
    fun slot(daily: Float, total: Float, sex: UserProfile.Sex?): Double =
        (score(total.toDouble(), sex) - score((total - daily).toDouble(), sex)).coerceAtLeast(0.0)

    /**
     * What leaves the total tomorrow, straight from the strap's projection: [tomorrowTotal] is `next[1]`, the total
     * tomorrow with no new activity (native 08 §3.7). Null when the record didn't carry one.
     */
    fun dropsOffTomorrow(total: Float, tomorrowTotal: Float?): Double? =
        tomorrowTotal?.let { (total - it).toDouble().coerceAtLeast(0.0) }

    /** A day's last PAI record. */
    data class Day(val daily: Float, val total: Float)

    /**
     * PAI that leaves the total tomorrow with no new activity: `total − next[1]` (native 08 §3.7), where next[1]
     * re-sums the six newest slots. [week] is 7 days, oldest first, today last; a day with no record counts as
     * empty. Today's own total is used when there is one, else the slots' total.
     */
    fun dropsOffTomorrow(week: List<Day?>, sex: UserProfile.Sex?): Double {
        require(week.size == 7) { tr(R.string.pai_need_days_got, week.size) }
        val slots = week.map { d -> d?.let { slot(it.daily, it.total, sex) } ?: 0.0 }
        val now = week.last()?.total?.toDouble() ?: total(slots, sex)
        return (now - total(slots.drop(1), sex)).coerceAtLeast(0.0)
    }
}
