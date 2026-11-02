package org.openstrap.app.data

import org.openstrap.protocol.services.StepTotals
import java.time.LocalDate

/**
 * Today's step count from synced minutes plus the strap's live counter.
 *
 * A plausible live total is used as is (it also covers minutes another app already fetched). On firmware 3.25
 * the live total carries a large constant offset (11,368 steps with 1,836 m), while its increments stay right.
 * Then the counter is anchored to the synced history: each time the history total changes, the live reading
 * at that moment is remembered, and only the steps counted since are added on top.
 *
 * The anchor is state, so the app keeps one instance, built once at start, that Today and the
 * widgets share: a fresh one would show only the history total until the next sync (audit A5).
 */
class LiveSteps {
    private var day: LocalDate? = null
    private var anchoredHistory = -1
    private var liveAtAnchor = 0

    /** [date]'s steps: a new day starts a new anchor, so yesterday's never carries over. */
    @Synchronized
    fun today(date: LocalDate, history: Int, live: StepTotals?): Int {
        if (date != day) {
            day = date
            anchoredHistory = -1
            liveAtAnchor = 0
        }
        return count(history, live)
    }

    private fun count(history: Int, live: StepTotals?): Int = when {
        live == null -> history
        live.isPlausible -> maxOf(history, live.steps)
        else -> {
            // Re-anchor on new synced data, and when the counter goes backwards (midnight reset or strap restart).
            if (history != anchoredHistory || live.steps < liveAtAnchor) {
                anchoredHistory = history
                liveAtAnchor = live.steps
            }
            history + (live.steps - liveAtAnchor)
        }
    }
}
