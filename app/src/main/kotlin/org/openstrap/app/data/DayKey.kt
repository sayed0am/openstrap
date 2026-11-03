package org.openstrap.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A stored day: its calendar date as the epoch second of that date's UTC midnight (3 Nov is 2026-11-03T00:00Z), the
 * same in every zone (item 17, user 2026-11-03). Rows keyed by a day keep their date after the phone changes zone,
 * where a local midnight would land on another date. Moments stay epoch seconds, and [Days] keeps the bounds of a day
 * in a zone. Room stores a [LocalDate] as this key ([org.openstrap.app.data.db.DayKeyConverter]).
 */
object DayKey {
    const val SECONDS = 86_400L

    /** Half a day: an old key at or past local noon is taken as the next date's midnight ([fromLegacy]). */
    private const val HALF_DAY = SECONDS / 2

    fun of(date: LocalDate): Long = date.toEpochDay() * SECONDS

    fun date(key: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(key, SECONDS))

    /** The day [epoch] falls on in the phone's [zone]. */
    fun dayOf(epoch: Long, zone: ZoneId): LocalDate = Days.date(epoch, zone)

    /** The day [epoch] falls on where it happened, by its own [offset]: a Health Connect record's. */
    fun dayOf(epoch: Long, offset: ZoneOffset): LocalDate = Instant.ofEpochSecond(epoch).atOffset(offset).toLocalDate()

    /**
     * An old key, the local midnight some zone had for its day (2.2.2 and the builds up to database version 27), as its
     * date in [zone]: the nearest local midnight, so a local time at or after noon is the next date. A key written in
     * [zone] comes back exactly, on days of 23 or 25 hours too. One written in another zone comes back right while the
     * two zones are under 12 hours apart, and a day off at 12 hours or more (Auckland to London), which can't be told
     * apart without a zone column. Migration and old backups only.
     */
    fun fromLegacy(old: Long, zone: ZoneId): LocalDate {
        val local = Instant.ofEpochSecond(old).atZone(zone)
        val date = local.toLocalDate()
        return if (local.toLocalTime().toSecondOfDay() >= HALF_DAY) date.plusDays(1) else date
    }

    /** [date]'s local midnight in [zone], as the old keys had it: Health Connect ids and the backup's older columns. */
    fun legacyMidnight(date: LocalDate, zone: ZoneId): Long = Days.start(date, zone)

    /**
     * The dates whose local midnight in [zone] falls in [from, to]: what a read of moments `day BETWEEN from AND to`
     * gave when days were stored as local midnights, kept for the callers that still read a day table by a span of
     * moments. Empty when no midnight is in it.
     */
    fun datesIn(from: Long, to: Long, zone: ZoneId): ClosedRange<LocalDate> {
        val first = Days.date(from, zone).let { if (Days.start(it, zone) < from) it.plusDays(1) else it }
        return first..Days.date(to, zone)
    }
}
