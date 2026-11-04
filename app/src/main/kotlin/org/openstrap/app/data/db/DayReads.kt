package org.openstrap.app.data.db

import org.openstrap.app.data.DayKey
import java.time.ZoneId

// Reads of the day tables by a span of moments, for the callers that work in moments: the days whose local midnight in
// the zone falls in [from, to] (DayKey.datesIn), the rows a read of local-midnight keys gave before day keys became dates.

suspend fun HealthDao.restingHrIn(from: Long, to: Long, zone: ZoneId): List<RestingHeartRate> =
    DayKey.datesIn(from, to, zone).let { restingHrOnce(it.start, it.endInclusive) }

suspend fun HealthDao.dailyMaxHrIn(from: Long, to: Long, zone: ZoneId): List<DailyMaxHr> =
    DayKey.datesIn(from, to, zone).let { dailyMaxHrOnce(it.start, it.endInclusive) }

suspend fun HealthDao.strapReadinessIn(from: Long, to: Long, zone: ZoneId): List<StrapReadinessRow> =
    DayKey.datesIn(from, to, zone).let { strapReadinessOnce(it.start, it.endInclusive) }
