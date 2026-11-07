package org.openstrap.app.device

/**
 * When the strap's clock is written outside connect, as Zepp 10.10.5 decides it (codemap zepp-connected-traffic-10.10.5
 * §1.4): on every other strap ping since the app started (`HMCoreService.a`, pings 1, 3, 5...), on TIME_SET and
 * DATE_CHANGED more than 60 s after the last broadcast write, and on TIMEZONE_CHANGED when the raw offset moved
 * (`hz90.a`). After every finished sync is the coordinator's.
 */
internal class ClockWrites {
    private var pings = 0
    private var lastBroadcastMs = 0L
    private var lastRawOffset = -1

    /** Counts a ping; true when it should write the clock. */
    @Synchronized
    fun onPing(): Boolean = (pings++ and 1) == 0

    /** A time broadcast [action] at [nowMs] with the zone's [rawOffset] (ms); true when it should write the clock. */
    @Synchronized
    fun onBroadcast(action: String, nowMs: Long, rawOffset: Int): Boolean = when (action) {
        TIMEZONE_CHANGED -> (lastRawOffset != rawOffset).also { if (it) lastRawOffset = rawOffset }
        TIME_SET, DATE_CHANGED -> (kotlin.math.abs(nowMs - lastBroadcastMs) > GAP_MS).also { if (it) lastBroadcastMs = nowMs }
        else -> false
    }

    companion object {
        const val TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
        const val TIME_SET = "android.intent.action.TIME_SET"
        const val DATE_CHANGED = "android.intent.action.DATE_CHANGED"
        const val GAP_MS = 60_000L
    }
}
