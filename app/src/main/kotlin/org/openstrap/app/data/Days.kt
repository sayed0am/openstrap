package org.openstrap.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A local day's bounds in epoch seconds. A day runs from its local midnight to the next one, which is 23 or 25 hours
 * on when the clocks change, so it's never `start + 86400`. Where a clock change skips midnight, the day starts at the
 * first moment it has.
 */
object Days {
    /** The local date [epoch] falls on in [zone]. */
    fun date(epoch: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()

    /** The local midnight that starts [date]. */
    fun start(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toEpochSecond()

    /** The local midnight after [date], the first second not in it. */
    fun end(date: LocalDate, zone: ZoneId): Long = start(date.plusDays(1), zone)

    /** The local midnight that starts the day [epoch] falls in. */
    fun start(epoch: Long, zone: ZoneId): Long = start(date(epoch, zone), zone)

    /** The local midnight after the day [epoch] falls in, the first second not in it. */
    fun end(epoch: Long, zone: ZoneId): Long = end(date(epoch, zone), zone)
}
