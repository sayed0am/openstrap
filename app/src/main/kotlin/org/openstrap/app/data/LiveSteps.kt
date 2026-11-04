package org.openstrap.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
 *
 * The strap's reading carries no date, and it stays as it was while the strap is away. So the instance [follow]s the
 * readings and keeps the local day each one came on: after midnight the last one is yesterday's total, and it counts
 * for nothing until the strap sends a new one (data integrity item 10).
 */
class LiveSteps {
    private var day: LocalDate? = null
    private var anchoredHistory = -1
    private var liveAtAnchor = 0

    /** The last reading the strap sent and the local day it came on, from [read]. */
    private var lastReading: StepTotals? = null
    private var readOn: LocalDate? = null

    /** Keeps the day of each reading of [readings] as it comes, [today] being the local date then, for as long as [scope] lives. */
    fun follow(readings: Flow<StepTotals?>, scope: CoroutineScope, today: () -> LocalDate = { LocalDate.now() }) {
        scope.launch { readings.collect { read(it, today()) } }
    }

    /** The strap sent [live] on [date]. */
    @Synchronized
    fun read(live: StepTotals?, date: LocalDate) {
        if (live == null) return
        lastReading = live
        readOn = date
    }

    /** [date]'s steps: a new day starts a new anchor, so yesterday's never carries over. */
    @Synchronized
    fun today(date: LocalDate, history: Int, live: StepTotals?): Int {
        if (date != day) {
            day = date
            anchoredHistory = -1
            liveAtAnchor = 0
        }
        return count(history, current(date, live))
    }

    /**
     * [live] when it counts on [date], else null: a reading that came on another day is that day's total. One not seen
     * yet has only just come, so it is today's. Leaves the anchor alone, for a caller that has no history to count from.
     */
    @Synchronized
    fun current(date: LocalDate, live: StepTotals?): StepTotals? = live?.takeUnless { it == lastReading && readOn != date }

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
