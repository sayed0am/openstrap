package org.openstrap.app.data.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.DayKey
import org.openstrap.app.data.Days
import org.openstrap.app.testing.MigrationHarness
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Version 27 to 28 (item 17): a 2.2.2 database (version 24, built from its exported schema) whose days are local
 * midnights opens with every day as its date's UTC midnight, in each zone the phone can be in when it first opens,
 * clock change days included, with every row and every moment kept. Then the cases a zone change in between makes:
 * keys from another zone, the 12 hour limit, and two rows that become one date.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayKeyMigrationTest {
    @get:Rule val harness = MigrationHarness()

    private val dubai = ZoneId.of("Asia/Dubai")
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val kathmandu = ZoneId.of("Asia/Kathmandu")

    private val ordinary = listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 6, 10), LocalDate.of(2026, 10, 30))

    /** Each zone's clock changes in 2026: the days of 23 and 25 hours. */
    private val clockChanges = mapOf(
        london to listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25)),
        newYork to listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1)),
        auckland to listOf(LocalDate.of(2026, 4, 5), LocalDate.of(2026, 9, 27)),
    )

    /** Tables that hold one row per day, and their day column. */
    private val dayTables = DayKeyMerge.TABLES.map { it.name to it.column }

    private fun SQLiteDatabase.put(sql: String, vararg args: Any) = execSQL(sql, args)

    /** One row a day in every day table, keyed by [key] (the old key for that date), plus two tags a day. */
    private fun SQLiteDatabase.seedDays(dates: List<LocalDate>, updated: Long = 1000, key: (LocalDate) -> Long) {
        dates.forEachIndexed { i, d ->
            val k = key(d)
            put("INSERT INTO resting_hr (day, bpm) VALUES (?, ?)", k, 50 + i)
            put("INSERT INTO daily_max_hr (day, bpm) VALUES (?, ?)", k, 170 + i)
            put(
                "INSERT INTO strap_readiness (day, updatedAt, score, sleepHrv, sleepRestingHr, algorithmVersion) VALUES (?, ?, ?, ?, ?, '4.3')",
                k, updated + i, 60 + i, 40 + i, 50 + i,
            )
            put("INSERT INTO check_in (day, feeling, updatedAt) VALUES (?, ?, ?)", k, 1 + i % 5, updated + i)
            put("INSERT INTO day_tag (day, tag) VALUES (?, 'alcohol')", k)
            put("INSERT INTO day_tag (day, tag) VALUES (?, 'travel')", k)
            put(
                "INSERT INTO night_metrics (day, updatedAt, hrvLn, hrvSamples, rhrLowest30, hrMinutes) VALUES (?, ?, 3.9, 40, ?, 420)",
                k, updated + i, 48.5 + i,
            )
            put("INSERT INTO habit_age_week (asOf, estimate, low, high, updatedAt) VALUES (?, ?, 30, 34, ?)", k, 31.5 + i, updated + i)
            put(
                "INSERT INTO daily_energy (day, updatedAt, totalKcal, activeKcal, bmrKcal, minutesWithData, partial, workoutKcal, walkingKcal, hourly) " +
                    "VALUES (?, ?, ?, 400, 1650, 1440, 0, 120, 200, '1.0,2.5')",
                k, updated + i, 2100 + i,
            )
        }
    }

    /** What the migration must leave as it was: moments, in tables of moments. */
    private fun SQLiteDatabase.seedMoments(zone: ZoneId, dates: List<LocalDate>) {
        dates.forEach { d ->
            val wake = d.atTime(7, 12).atZone(zone).toEpochSecond()
            put("INSERT INTO heart_rate (timestamp, bpm, source) VALUES (?, 61, 0)", wake)
            put("INSERT INTO heart_rate (timestamp, bpm, source) VALUES (?, 64, 1)", wake + 60)
            put("INSERT INTO osa_result (belongTime, validTime, odiRaw, odiScore, dispCode, eventCount, version) VALUES (?, 300, 2, 90, 0, 3, 1)", wake - 9 * 3600)
        }
    }

    private fun rows(db: OpenStrapDatabase, sql: String): List<List<Any?>> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    (0 until c.columnCount).map {
                        when (c.getType(it)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(it)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(it)
                            else -> c.getString(it)
                        }
                    },
                )
            }
        }
    }

    private fun keys(db: OpenStrapDatabase, table: String, column: String) =
        rows(db, "SELECT `$column` FROM `$table` ORDER BY `$column`").map { it.single() as Long }

    @Test fun `a 2_2_2 database opens with every day as its date in each zone, clock changes included`() = runTest {
        for (zone in listOf(dubai, london, newYork, auckland, kathmandu)) {
            val dates = (ordinary + clockChanges[zone].orEmpty()).sorted()
            val file = harness.create(24) {
                seedDays(dates) { Days.start(it, zone) }
                seedMoments(zone, dates)
                // A sleep from before v2 (day 0) and one the strap gave a day.
                put("INSERT INTO sleep_session (start, `end`, day, nap) VALUES (?, ?, 0, 0)", 1_700_000_000L, 1_700_028_800L)
                put("INSERT INTO sleep_session (start, `end`, day, nap, score) VALUES (?, ?, ?, 0, 81)", 1_780_000_000L, 1_780_028_800L, Days.start(dates[1], zone))
                put("INSERT INTO nap_edit (strapStart, start, `end`) VALUES (1780000000, 1780000600, 1780028800)")
            }
            val before = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun count(t: String) = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getLong(0) }
                dayTables.associate { (t, _) -> t to count(t) } + ("sleep_session" to count("sleep_session"))
            }

            val db = harness.open(file, zone)
            val expected = dates.map(DayKey::of)
            for ((table, column) in dayTables) {
                val want = if (table == "day_tag") expected.flatMap { listOf(it, it) } else expected
                assertEquals("$table in $zone", want, keys(db, table, column))
                assertEquals("$table rows in $zone", before[table], rows(db, "SELECT COUNT(*) FROM $table").single().single())
            }
            assertEquals("sleep in $zone", listOf(0L, DayKey.of(dates[1])), keys(db, "sleep_session", "day"))
            assertEquals(before["sleep_session"], rows(db, "SELECT COUNT(*) FROM sleep_session").single().single())
            // The values beside each key stay with it.
            assertEquals(dates.indices.map { 50L + it }, rows(db, "SELECT bpm FROM resting_hr ORDER BY day").map { it.single() })
            assertEquals(dates.indices.map { 2100.0 + it }, rows(db, "SELECT totalKcal FROM daily_energy ORDER BY day").map { it.single() })
            // Moments are left exactly as they were.
            val wakes = dates.map { it.atTime(7, 12).atZone(zone).toEpochSecond() }
            assertEquals(wakes.flatMap { listOf(listOf(it, 61L, 0L), listOf(it + 60, 64L, 1L)) }, rows(db, "SELECT * FROM heart_rate ORDER BY timestamp"))
            assertEquals(wakes.map { it - 9 * 3600 }, keys(db, "osa_result", "belongTime"))
            assertEquals(listOf(listOf(1_780_000_000L, 1_780_000_600L, 1_780_028_800L)), rows(db, "SELECT strapStart, start, `end` FROM sleep_edit"))
            assertEquals(30, db.openHelper.readableDatabase.version)
            // Room reads them as the dates.
            val dao = db.healthDao()
            assertEquals(dates, dao.restingHrAll().map { it.day })
            assertEquals(dates, dao.checkInsAll().map { it.day })
            assertEquals(dates[1], dao.sleepSessionsOnce(0, Long.MAX_VALUE / 2).single { it.score == 81 }.strapDate)
            db.close()
        }
    }

    @Test fun `days written in Dubai and opened in New York keep their dates`() = runTest {
        val dates = ordinary + clockChanges.getValue(newYork)
        val db = harness.open(harness.create(24) { seedDays(dates) { Days.start(it, dubai) } }, newYork)
        assertEquals(dates.sorted(), db.healthDao().restingHrAll().map { it.day })
        assertEquals(dates.sorted(), db.healthDao().dailyCaloriesAll().map { it.day })
    }

    @Test fun `days written in Auckland and opened in London come back a day early, the 12 hour limit`() = runTest {
        // Auckland in summer is 13 hours ahead of London in winter: its midnight is 11:00 the day before in London.
        val summer = LocalDate.of(2026, 1, 15)
        // In the southern winter (12 hours) and the British summer (1 hour) it's 13:00 the day before, which rounds up.
        val winter = LocalDate.of(2026, 6, 10)
        val db = harness.open(harness.create(24) { seedDays(listOf(summer, winter)) { Days.start(it, auckland) } }, london)
        assertEquals(listOf(summer.minusDays(1), winter), db.healthDao().restingHrAll().map { it.day })
    }

    @Test fun `two keys for one date keep one row by the merge rules`() = runTest {
        val d = LocalDate.of(2026, 10, 30)
        val inDubai = Days.start(d, dubai)
        val inNewYork = Days.start(d, newYork)
        val file = harness.create(24) {
            // Written in Dubai first, then again after a flight to New York, newer.
            seedDays(listOf(d), updated = 1000) { inDubai }
            put("INSERT INTO resting_hr (day, bpm) VALUES (?, 58)", inNewYork)
            put("INSERT INTO daily_max_hr (day, bpm) VALUES (?, 165)", inNewYork)
            put("INSERT INTO strap_readiness (day, updatedAt, score, algorithmVersion) VALUES (?, 2000, 77, '4.3')", inNewYork)
            put("INSERT INTO check_in (day, feeling, updatedAt) VALUES (?, 5, 2000)", inNewYork)
            put("INSERT INTO day_tag (day, tag) VALUES (?, 'alcohol')", inNewYork)
            put("INSERT INTO day_tag (day, tag) VALUES (?, 'late_meal')", inNewYork)
            put("INSERT INTO night_metrics (day, updatedAt, hrvSamples, hrMinutes) VALUES (?, 500, 10, 100)", inNewYork)
            put("INSERT INTO habit_age_week (asOf, estimate, low, high, updatedAt) VALUES (?, 29.0, 27, 31, 2000)", inNewYork)
            put(
                "INSERT INTO daily_energy (day, updatedAt, totalKcal, activeKcal, bmrKcal, minutesWithData, partial, workoutKcal, walkingKcal, hourly) " +
                    "VALUES (?, 2000, 2500, 400, 1650, 1440, 0, 120, 200, '')",
                inNewYork,
            )
        }
        val db = harness.open(file, dubai)
        val dao = db.healthDao()
        // Resting HR: the key that is this date's midnight here, Dubai's, as a sync here would write.
        assertEquals(listOf(RestingHeartRate(d, 50)), dao.restingHrAll())
        // The day's highest, as the strap's writes keep.
        assertEquals(listOf(DailyMaxHr(d, 170)), dao.dailyMaxHrAll())
        // The newest answer or estimate.
        assertEquals(listOf(5 to 2000L), dao.checkInsAll().map { it.feeling to it.updatedAt })
        assertEquals(listOf(77), dao.strapReadinessAll().map { it.score })
        assertEquals(listOf(1000L), dao.nightMetricsAll().map { it.updatedAt })
        assertEquals(listOf(29.0f), dao.habitAgeWeeksAll().map { it.estimate })
        assertEquals(listOf(2500f), dao.dailyCaloriesAll().map { it.totalKcal })
        // Every tag either key had, once.
        assertEquals(listOf("alcohol", "late_meal", "travel"), dao.dayTagsAll().map { it.tag })
        assertEquals(setOf(d), dao.dayTagsAll().map { it.day }.toSet())
    }

    @Test fun `two strap days a 12 hour flight apart share a key and keep both sleeps`() = runTest {
        // The strap's day 15 January written in Auckland, and 14 January written in London: both are 14 January here.
        val inAuckland = Days.start(LocalDate.of(2026, 1, 15), auckland)
        val inLondon = Days.start(LocalDate.of(2026, 1, 14), london)
        val file = harness.create(24) {
            put("INSERT INTO sleep_session (start, `end`, day, nap) VALUES (?, ?, ?, 0)", inAuckland - 3 * 3600, inAuckland + 5 * 3600, inAuckland)
            put("INSERT INTO sleep_session (start, `end`, day, nap) VALUES (?, ?, ?, 0)", inLondon - 3 * 3600, inLondon + 5 * 3600, inLondon)
        }
        val db = harness.open(file, london)
        assertEquals(List(2) { DayKey.of(LocalDate.of(2026, 1, 14)) }, keys(db, "sleep_session", "day"))
    }

    @Test fun `a version 26 database reaches the latest version with its badges, weeks and bests untouched`() = runTest {
        val d = LocalDate.of(2026, 10, 30)
        val file = harness.create(26) {
            seedDays(listOf(d)) { Days.start(it, newYork) }
            put("INSERT INTO badge (id, earnedAt, workoutStart, day) VALUES ('steps_10k', 1790000000, NULL, ?)", d.toEpochDay())
            put("INSERT INTO week_result (weekStart, goal, count, met) VALUES (?, 3, 4, 1)", d.toEpochDay())
            put("INSERT INTO personal_best (kind, value, date, workoutStart) VALUES ('MOST_STEPS', 15000, 1790000000, NULL)")
        }
        val db = harness.open(file, newYork)
        assertEquals(30, db.openHelper.readableDatabase.version)
        assertEquals(listOf(d), db.healthDao().restingHrAll().map { it.day })
        assertEquals(listOf(listOf("steps_10k", 1_790_000_000L, null, d.toEpochDay())), rows(db, "SELECT * FROM badge"))
        assertEquals(listOf(listOf(d.toEpochDay(), 3L, 4L, 1L)), rows(db, "SELECT * FROM week_result"))
        assertEquals(listOf(listOf("MOST_STEPS", 15000.0, 1_790_000_000L, null)), rows(db, "SELECT * FROM personal_best"))
    }
}
