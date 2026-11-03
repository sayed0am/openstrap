package org.openstrap.app.health

import org.openstrap.app.data.DayKey
import org.openstrap.app.data.Days
import org.openstrap.app.data.db.DailyCaloriesRow
import java.time.LocalDate
import java.time.ZoneId

/**
 * What Health Connect gets of the stored days' calories ([DailyCaloriesRow], calories spec 5): active kcal an hour, and
 * the day's total once it's over and not partial. Workouts get none of their own: Health Connect's exercise session
 * has no kcal, and a record over the workout would count its minutes again on top of their hours. Pure, so the split
 * is tested without Health Connect.
 */
object CalorieRecords {
    /** One record to write: its stable [id] (clientRecordId, less the app's prefix), its time and kcal. */
    data class Span(val id: String, val start: Long, val end: Long, val kcal: Double, val version: Long)

    /**
     * Each local hour of [rows] with active kcal in it, as its own record: hour 0 to 23 from the day's local midnight
     * in [zone], cut at [now] so the hour under way never ends in the future. An hour with none writes nothing. Ids
     * are the hour's start, as they were when days were stored as local midnights, so a day written again replaces
     * its records.
     */
    fun activeHours(rows: List<DailyCaloriesRow>, zone: ZoneId, now: Long): List<Span> = rows.flatMap { row ->
        val date = row.day
        row.hourlyKcal.withIndex().mapNotNull { (h, kcal) ->
            if (h > 23 || kcal <= 0f) return@mapNotNull null
            // A clock gap (spring forward) moves the hour on; that hour holds nothing anyway.
            val start = date.atTime(h, 0).atZone(zone).toEpochSecond()
            val end = minOf(start + 3600, now)
            if (end <= start) return@mapNotNull null
            Span("active:$start", start, end, kcal.toDouble(), row.updatedAt)
        }
    }

    /**
     * The day's total kcal, local midnight to midnight, for each of [rows] that's over by [now] and not partial. Its id
     * is the day's local midnight in [zone] ([DayKey.legacyMidnight]), the id it had before days were stored as dates.
     */
    fun dayTotals(rows: List<DailyCaloriesRow>, zone: ZoneId, now: Long): List<Span> = rows.mapNotNull { row ->
        val start = DayKey.legacyMidnight(row.day, zone)
        val end = Days.end(row.day, zone)
        if (row.partial || end > now || row.totalKcal <= 0f) return@mapNotNull null
        Span("total:$start", start, end, row.totalKcal.toDouble(), row.updatedAt)
    }

    /** Local midnight of the day [epoch] falls in, in [zone]. */
    fun dayStart(epoch: Long, zone: ZoneId): Long = Days.start(epoch, zone)

    /** The next local midnight after [day] (itself one), 23 to 25 hours on. */
    fun dayEnd(day: Long, zone: ZoneId): Long = Days.end(day, zone)

    /** The span of whole local days [from] to [to] are in, for the stored days to write and the old records to clear. */
    fun days(from: Long, to: Long, zone: ZoneId): Pair<Long, Long> = dayStart(from, zone) to dayEnd(dayStart(maxOf(from, to), zone), zone)

    /** The stored days of [days]' span (`a` to `b`, from [days]): those whose local midnight is in it. */
    fun dates(span: Pair<Long, Long>, zone: ZoneId): ClosedRange<LocalDate> = DayKey.datesIn(span.first, span.second - 1, zone)
}
