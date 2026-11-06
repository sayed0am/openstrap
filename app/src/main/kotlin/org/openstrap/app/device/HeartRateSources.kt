package org.openstrap.app.device

/**
 * Picks one heart rate source when the strap sends several at once.
 *
 * - [Source.SESSION]: the `14` frames of a workout session on 0x0019, once a second. They say 0 when the strap has
 *   no reading, e.g. off the body. They win whenever one arrived in the last [SESSION_FRESH_MS]. This is OpenStrap's
 *   own choice: Zepp only shows them (`h6v.g` to the screen's `onHrChanged`, 0 drawn as "--") and records the
 *   0x0048 stream (`g0j.f`), which can't tell when the strap is off the body.
 * - [Source.CHANNEL]: the 0x0048 live push. The fallback, for sports the strap runs no session for (climbing) and
 *   outside workouts. It has no contact field, and a user saw it stuck near 80 bpm in a hard workout and off the arm.
 * - [Source.MEASUREMENT]: standard 2A37, subscribed only on a strap without 0x0048, as in Zepp; still held off while
 *   a 0x0048 push came in the last [CHANNEL_FRESH_MS], so the two are never mixed.
 *
 * Not thread safe: callers hold one lock around it. Times are in milliseconds from one clock.
 */
internal class HeartRateSources {
    enum class Source { SESSION, CHANNEL, MEASUREMENT }

    sealed interface Decision {
        /** Show and record [bpm]. */
        data class Reading(val bpm: Int) : Decision
        /** The chosen source has no reading: show none, record nothing. */
        data object NoReading : Decision
        /** Another source is in charge: drop it. */
        data object Ignore : Decision
    }

    private var lastSession = Long.MIN_VALUE
    private var lastChannel = Long.MIN_VALUE
    private var lastShown = Long.MIN_VALUE

    /** [bpm] null with [noReading] false means the frame had no usable heart rate: it still counts as the source alive. */
    fun offer(source: Source, bpm: Int?, noReading: Boolean, now: Long): Decision {
        // Checked before this source's own time is noted, so the channel doesn't hold itself off.
        val suppressed = suppressed(source, now)
        when (source) {
            Source.SESSION -> lastSession = now
            Source.CHANNEL -> lastChannel = now
            Source.MEASUREMENT -> Unit
        }
        return when {
            suppressed -> Decision.Ignore
            noReading -> {
                lastShown = Long.MIN_VALUE
                Decision.NoReading
            }
            bpm == null -> Decision.Ignore
            else -> {
                lastShown = now
                Decision.Reading(bpm)
            }
        }
    }

    /** Whether a higher source spoke recently enough that [source] is dropped. */
    private fun suppressed(source: Source, now: Long): Boolean = when (source) {
        Source.SESSION -> false
        Source.CHANNEL -> fresh(lastSession, SESSION_FRESH_MS, now)
        Source.MEASUREMENT -> fresh(lastSession, SESSION_FRESH_MS, now) || fresh(lastChannel, CHANNEL_FRESH_MS, now)
    }

    /** True when a reading is shown but none came for [STALE_MS]: the shown number should go. */
    fun isStale(now: Long): Boolean = lastShown != Long.MIN_VALUE && !fresh(lastShown, STALE_MS, now)

    /** The shown reading has been cleared. */
    fun cleared() {
        lastShown = Long.MIN_VALUE
    }

    /** A new link: nothing heard yet. */
    fun reset() {
        lastSession = Long.MIN_VALUE
        lastChannel = Long.MIN_VALUE
        lastShown = Long.MIN_VALUE
    }

    private fun fresh(at: Long, window: Long, now: Long) = at != Long.MIN_VALUE && now - at < window

    companion object {
        /** Session frames come once a second; after this long without one the live stream takes over. */
        const val SESSION_FRESH_MS = 5_000L
        const val CHANNEL_FRESH_MS = 3_000L
        /** Zepp's watchdog on the session frames is 10 s; a number older than this isn't shown. */
        const val STALE_MS = 10_000L
    }
}
