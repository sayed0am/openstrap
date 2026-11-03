package org.openstrap.app.export

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.DayKey
import org.openstrap.app.data.Days
import org.openstrap.app.data.db.CheckInRow
import org.openstrap.app.data.db.DailyEnergyRow
import org.openstrap.app.data.db.DayTagRow
import org.openstrap.app.data.db.OpenStrapDatabase
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.testing.ZoneRule
import org.openstrap.app.testing.inMemoryDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups and day keys as dates (item 17): a 2.2.2 backup's local midnights restore as their dates in the phone's zone,
 * two of them for one date keep one row by the migration's rules, and a new backup carries its dates exactly, with the
 * older columns still written for the builds before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvDayKeyTest {
    @get:Rule val zoneRule = ZoneRule()

    private val dubai = ZoneId.of("Asia/Dubai")
    private val newYork = ZoneId.of("America/New_York")
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val london = ZoneId.of("Europe/London")
    private val d = LocalDate.of(2026, 1, 15)

    private lateinit var old: OpenStrapDatabase
    private lateinit var phone: OpenStrapDatabase

    @Before fun open() { old = inMemoryDb(); phone = inMemoryDb() }
    @After fun close() { old.close(); phone.close() }

    private fun inZone(zone: ZoneId) = TimeZone.setDefault(TimeZone.getTimeZone(zone))

    private fun zip(files: Map<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private fun files(zip: ByteArray): Map<String, String> {
        val z = ZipInputStream(ByteArrayInputStream(zip))
        return buildMap { while (true) { val e = z.nextEntry ?: break; put(e.name, z.readBytes().toString(Charsets.UTF_8)) } }
    }

    private suspend fun restore(zip: ByteArray) = CsvImporter(phone).importZip(ByteArrayInputStream(zip))

    private fun midnight(date: LocalDate, zone: ZoneId) = Days.start(date, zone)
    private fun iso(epoch: Long) = Instant.ofEpochSecond(epoch).toString()

    @Test fun `a 2_2_2 backup made in Dubai restores in New York on its dates`() = runTest {
        val m = midnight(d, dubai)
        val next = midnight(d.plusDays(1), dubai)
        // Dubai's night ending at 07:00 on d, under the strap's day: its local midnight.
        val sleepStart = m - 3 * 3600
        val sleepEnd = m + 7 * 3600
        val zip = zip(
            mapOf(
                "resting_hr.csv" to "day,epoch,bpm\n${iso(m)},$m,52\n${iso(next)},$next,54\n",
                "check_in.csv" to "day,day_epoch,feeling,updated_epoch\n${iso(m)},$m,4,${m + 3600}\n",
                "day_tag.csv" to "day,day_epoch,tag\n${iso(m)},$m,alcohol\n",
                "sleep_session.csv" to "start,end,start_epoch,end_epoch,nap,score,avg_hr,deep_min,light_min,rem_min,awake_min,wake_count,day_epoch\n" +
                    "${iso(sleepStart)},${iso(sleepEnd)},$sleepStart,$sleepEnd,false,80,55,60,200,90,20,3,$m\n",
            ),
        )
        inZone(newYork)
        val result = restore(zip)
        assertEquals(0, result.skipped)
        val dao = phone.healthDao()
        assertEquals(listOf(RestingHeartRate(d, 52), RestingHeartRate(d.plusDays(1), 54)), dao.restingHrAll().sortedBy { it.day })
        assertEquals(listOf(CheckInRow(d, 4, m + 3600)), dao.checkInsAll())
        assertEquals(listOf(DayTagRow(d, "alcohol")), dao.dayTagsAll())
        assertEquals(DayKey.of(d), dao.sleepSessionsOnce(0, Long.MAX_VALUE / 2).single().day)
    }

    @Test fun `two old keys for one date in one file keep the row the migration would`() = runTest {
        val fromDubai = midnight(d, dubai)
        val fromNewYork = midnight(d, newYork)
        // Both read as d in London: Dubai's midnight is 20:00 the day before there, New York's 05:00.
        val zip = zip(
            mapOf(
                // Resting HR keeps the nearer midnight: Dubai's, 4 hours off London's against New York's 5.
                "resting_hr.csv" to "day,epoch,bpm\n${iso(fromNewYork)},$fromNewYork,60\n${iso(fromDubai)},$fromDubai,50\n",
                // The highest maximum.
                "daily_max_hr.csv" to "day,epoch,bpm\n${iso(fromDubai)},$fromDubai,181\n${iso(fromNewYork)},$fromNewYork,176\n",
                // The answer given last, whichever line comes first.
                "check_in.csv" to "day,day_epoch,feeling,updated_epoch\n${iso(fromNewYork)},$fromNewYork,2,500\n${iso(fromDubai)},$fromDubai,5,400\n",
                // Every tag either had, once.
                "day_tag.csv" to "day,day_epoch,tag\n${iso(fromDubai)},$fromDubai,alcohol\n${iso(fromNewYork)},$fromNewYork,alcohol\n${iso(fromNewYork)},$fromNewYork,travel\n",
            ),
        )
        inZone(london)
        val result = restore(zip)
        assertEquals(0, result.skipped)
        val dao = phone.healthDao()
        assertEquals(listOf(RestingHeartRate(d, 50)), dao.restingHrAll())
        assertEquals(181, dao.dailyMaxHrAll().single().bpm)
        assertEquals(listOf(CheckInRow(d, 2, 500)), dao.checkInsAll())
        assertEquals(setOf(DayTagRow(d, "alcohol"), DayTagRow(d, "travel")), dao.dayTagsAll().toSet())
    }

    @Test fun `a backup made in Auckland restores in New York on exactly its dates`() = runTest {
        // 18 hours apart in January: an old key alone would come back a day early.
        val o = old.healthDao()
        o.insertRestingHr(listOf(RestingHeartRate(d, 55), RestingHeartRate(d.plusDays(1), 57)))
        o.insertCheckIns(listOf(CheckInRow(d, 3, 1000)))
        o.insertDayTags(listOf(DayTagRow(d, "late_meal")))
        o.insertDailyEnergy(listOf(DailyEnergyRow(d, 2000, 2100f, 450f, 1650f, 1440, false, 0f, 200f, List(24) { "1.5" }.joinToString(","))))
        o.insertSleepSessions(listOf(SleepSession(midnight(d, auckland) - 3 * 3600, midnight(d, auckland) + 7 * 3600, DayKey.of(d), score = 70)))
        inZone(auckland)
        val zip = ByteArrayOutputStream().also { CsvExporter(o).exportZip(it) }.toByteArray()

        // The older columns still hold Auckland's local midnights, for a 2.2.2 that restores it.
        val resting = files(zip).getValue("resting_hr.csv").lines()
        assertEquals("day,epoch,bpm,date", resting[0])
        assertEquals(listOf(iso(midnight(d, auckland)), midnight(d, auckland).toString(), "55", d.toString()), resting[1].split(','))
        val sleep = files(zip).getValue("sleep_session.csv").lines()
        val header = sleep[0].split(',')
        val row = sleep[1].split(',')
        assertEquals(midnight(d, auckland).toString(), row[header.indexOf("day_epoch")])
        assertEquals(d.toString(), row[header.indexOf("date")])

        inZone(newYork)
        restore(zip)
        val p = phone.healthDao()
        assertEquals(o.restingHrAll().sortedBy { it.day }, p.restingHrAll().sortedBy { it.day })
        assertEquals(o.checkInsAll(), p.checkInsAll())
        assertEquals(o.dayTagsAll(), p.dayTagsAll())
        assertEquals(o.dailyEnergyAll(), p.dailyEnergyAll())
        assertEquals(DayKey.of(d), p.sleepSessionsOnce(0, Long.MAX_VALUE / 2).single().day)
    }
}
