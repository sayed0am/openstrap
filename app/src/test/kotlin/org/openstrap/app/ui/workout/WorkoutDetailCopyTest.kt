package org.openstrap.app.ui.workout

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.workout.WorkoutDetailCopy.Effort

/** The page of a past workout: its sentence, facts, heart rate drop and the (i) on Load (spec 6.3). */
class WorkoutDetailCopyTest {
    // English figures: the German and Arabic ones are WorkoutWordsTest's and the presentation tests'.
    @get:org.junit.Rule val localeRule = org.openstrap.app.testing.LocaleRule()

    private val run = 1
    private val walk = 3
    private val strength = 52
    private val hiit = 49
    private val hour = 3600L

    @Test fun `effort words follow the Load card's bands against your usual`() {
        val usual = 100f
        assertEquals(Effort.Easy, WorkoutDetailCopy.effort(50, usual))
        assertEquals(Effort.Easy, WorkoutDetailCopy.effort(74, usual))
        assertEquals(Effort.Steady, WorkoutDetailCopy.effort(75, usual))
        assertEquals(Effort.Steady, WorkoutDetailCopy.effort(125, usual))
        assertEquals(Effort.Hard, WorkoutDetailCopy.effort(126, usual))
        assertEquals(Effort.Hard, WorkoutDetailCopy.effort(174, usual))
        assertEquals(Effort.VeryHard, WorkoutDetailCopy.effort(175, usual))
        assertEquals(Effort.VeryHard, WorkoutDetailCopy.effort(400, usual))
    }

    @Test fun `without a usual the fixed scale decides, and no Load has no word`() {
        assertEquals(Effort.Easy, WorkoutDetailCopy.effort(40, null))
        assertEquals(Effort.Steady, WorkoutDetailCopy.effort(80, null))
        assertEquals(Effort.Hard, WorkoutDetailCopy.effort(120, null))
        assertEquals(Effort.VeryHard, WorkoutDetailCopy.effort(150, null))
        assertNull(WorkoutDetailCopy.effort(null, 100f))
        assertNull(WorkoutDetailCopy.effort(0, 100f))
    }

    @Test fun `the sport reads mid-sentence, short nouns first, acronyms kept`() {
        assertEquals("run", WorkoutDetailCopy.sport(run))
        assertEquals("walk", WorkoutDetailCopy.sport(walk))
        assertEquals("ride", WorkoutDetailCopy.sport(4))
        assertEquals("strength training", WorkoutDetailCopy.sport(strength))
        assertEquals("HIIT", WorkoutDetailCopy.sport(hiit))
        assertEquals("HYROX training", WorkoutDetailCopy.sport(222))
        assertEquals("workout", WorkoutDetailCopy.sport(999))
    }

    @Test fun `mostly in the zone with the most time, none with too little`() {
        assertEquals(3, WorkoutDetailCopy.mostlyZone(listOf(60, 120, 300, 900, 100)))
        assertEquals(1, WorkoutDetailCopy.mostlyZone(listOf(0, 600, 0, 0, 0)))
        assertNull(WorkoutDetailCopy.mostlyZone(listOf(0, 0, 0, 0, 0)))
        assertNull(WorkoutDetailCopy.mostlyZone(listOf(10, 20, 20, 0, 0)))
        assertNull(WorkoutDetailCopy.mostlyZone(emptyList()))
    }

    @Test fun `the sentence names the effort, the sport and the zone`() {
        val z4 = listOf(60, 120, 300, 900, 100)
        val z2 = listOf(300, 1200, 60, 0, 0)
        assertEquals("Hard run, mostly in zone 4.", WorkoutDetailCopy.sentence(run, hour, 150, 100f, z4))
        assertEquals("Easy walk, mostly in zone 2.", WorkoutDetailCopy.sentence(walk, hour, 30, 100f, z2))
        assertEquals("Steady strength training, mostly in zone 2.", WorkoutDetailCopy.sentence(strength, hour, 100, 100f, z2))
        assertEquals("Very hard HIIT, mostly in zone 4.", WorkoutDetailCopy.sentence(hiit, hour, 300, 100f, z4))
    }

    @Test fun `without zone data the zone part goes`() {
        assertEquals("Hard run.", WorkoutDetailCopy.sentence(run, hour, 150, 100f, listOf(0, 0, 0, 0, 0)))
        assertEquals("Hard run.", WorkoutDetailCopy.sentence(run, hour, 150, 100f, emptyList()))
    }

    @Test fun `without Load it's just the sport`() {
        assertEquals("A run, mostly in zone 2.", WorkoutDetailCopy.sentence(run, hour, null, 100f, listOf(0, 600, 0, 0, 0)))
        assertEquals("Strength training.", WorkoutDetailCopy.sentence(strength, hour, null, null, emptyList()))
        assertEquals("HIIT.", WorkoutDetailCopy.sentence(hiit, hour, null, null, emptyList()))
    }

    @Test fun `a very short workout is short whatever its Load`() {
        assertEquals("Short run.", WorkoutDetailCopy.sentence(run, 4 * 60L, 200, 100f, listOf(0, 0, 0, 30, 0)))
        assertEquals("Short walk, mostly in zone 1.", WorkoutDetailCopy.sentence(walk, 4 * 60L, null, null, listOf(120, 0, 0, 0, 0)))
        assertEquals("Short strength training.", WorkoutDetailCopy.sentence(strength, 0L, null, null, emptyList()))
        assertTrue(WorkoutDetailCopy.sentence(run, WorkoutDetailCopy.SHORT_S, 150, 100f, emptyList()).startsWith("Hard"))
    }

    @Test fun `the day is relative when close, with the year when it's another`() {
        val today = LocalDate.of(2026, 10, 24)
        fun at(d: LocalDate) = d.atTime(18, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals("Today", WorkoutDetailCopy.day(at(today), today, ZoneOffset.UTC, Locale.UK))
        assertEquals("Yesterday", WorkoutDetailCopy.day(at(today.minusDays(1)), today, ZoneOffset.UTC, Locale.UK))
        // Within the week the weekday, as every day word reads, then the date.
        assertEquals("Wednesday", WorkoutDetailCopy.day(at(LocalDate.of(2026, 10, 21)), today, ZoneOffset.UTC, Locale.UK))
        assertEquals("Fri 16 Oct", WorkoutDetailCopy.day(at(LocalDate.of(2026, 10, 16)), today, ZoneOffset.UTC, Locale.UK))
        assertEquals("Fri 24 Oct 2025", WorkoutDetailCopy.day(at(LocalDate.of(2025, 10, 24)), today, ZoneOffset.UTC, Locale.UK))
    }

    @Test fun `facts leave out what there's nothing to say about`() {
        val all = WorkoutDetailCopy.facts(5210f, false, 4 * 60L + 12, 6201, 412, locale = Locale.UK)
        assertEquals(listOf("Distance", "Paused", "Steps", "Calories"), all.map { it.name })
        // Colours and icons go by the kind, not the words.
        assertEquals(
            listOf(WorkoutFact.Kind.Distance, WorkoutFact.Kind.Paused, WorkoutFact.Kind.Steps, WorkoutFact.Kind.Calories), all.map { it.kind },
        )
        assertEquals("5.21", all[0].value)
        assertEquals("Kilometres", all[0].status)
        assertEquals("4:12", all[1].value)
        assertEquals("6,201", all[2].value)
        val short = WorkoutDetailCopy.facts(430f, true, 30, null, 0, locale = Locale.UK)
        assertEquals(listOf("Distance"), short.map { it.name })
        assertEquals("430", short[0].value)
        assertEquals("Metres, estimated from your steps", short[0].status)
        assertTrue(WorkoutDetailCopy.facts(null, false, 0, null, null).isEmpty())
    }

    @Test fun `calories lead with active kcal and say the total under it`() {
        val both = WorkoutDetailCopy.facts(null, false, 0, null, 1612, activeKcal = 1060, locale = Locale.UK).single()
        assertEquals("1,060", both.value)
        assertEquals("Total 1,612", both.status)
        // A strap workout without a profile: the strap's own figure, in kcal.
        val strap = WorkoutDetailCopy.facts(null, false, 0, null, 412, activeKcal = null, locale = Locale.UK).single()
        assertEquals("412", strap.value)
        assertEquals("kcal", strap.status)
    }

    @Test fun `the drop shows only after a real effort, against your usual`() {
        assertEquals("Heart rate drop of 30 bpm in the first minute.", WorkoutDetailCopy.dropLine(30, true, null))
        assertEquals("Heart rate drop of 30 bpm in the first minute, quick for you.", WorkoutDetailCopy.dropLine(30, true, 24))
        assertEquals("Heart rate drop of 20 bpm in the first minute, slow for you.", WorkoutDetailCopy.dropLine(20, true, 26))
        assertEquals("Heart rate drop of 25 bpm in the first minute, about usual for you.", WorkoutDetailCopy.dropLine(25, true, 26))
        assertNull(WorkoutDetailCopy.dropLine(30, false, null))
        assertNull(WorkoutDetailCopy.dropLine(0, true, null))
        assertNull(WorkoutDetailCopy.dropLine(null, true, null))
    }

    @Test fun `the sheet explains Load, training effect and the rest with this workout's values`() {
        val s = WorkoutDetailCopy.effectSheet(
            aerobic = 3.2f, anaerobic = 1.1f, load = 85, usualLoad = 70f, recoveryMinutes = 840,
            drop60 = 30, drop120 = 48, endBpm = 160, meaningfulDrop = true, vo2max = 48.4f, fromPhone = false, strap = emptyList(),
        )
        assertEquals(2, s.here)
        assertEquals(listOf("Load", "Training effect", "Time to recover", "Heart rate drop", "VO2 max", "Where these come from"), s.rows.map { it.first })
        assertTrue(s.rows[0].second.contains("This workout: 85. Your usual is 70."))
        assertTrue(s.rows[1].second.contains("aerobic 3.2, anaerobic 1.1"))
        assertTrue(s.rows[2].second.contains("14 h"))
        assertTrue(s.rows[3].second.contains("down 30 in the first minute, 48 in two"))
        assertEquals(WorkoutDetailCopy.EFFECT_SCALE, s.info.scale)
    }

    @Test fun `a phone workout's sheet keeps only what it has, and the strap's own record`() {
        val s = WorkoutDetailCopy.effectSheet(
            aerobic = null, anaerobic = null, load = 60, usualLoad = null, recoveryMinutes = null,
            drop60 = null, drop120 = null, endBpm = null, meaningfulDrop = false, vo2max = null,
            fromPhone = true, strap = listOf(EffectFigures(3.0f, 0.8f, 80, 720)),
        )
        assertNull(s.here)
        assertNull(s.hereLine)
        assertTrue(s.info.scale.isEmpty())
        assertEquals(listOf("Load", "Where these come from"), s.rows.map { it.first })
        assertTrue(s.rows[1].second.contains("Its own figures: aerobic 3.0, anaerobic 0.8, Load 80, time to recover 12 h."))
    }

    @Test fun `a phone workout with the strap's own figures says so, and doesn't repeat them`() {
        val s = WorkoutDetailCopy.effectSheet(
            aerobic = 2.1f, anaerobic = 0.1f, load = 16, usualLoad = null, recoveryMinutes = 660,
            drop60 = null, drop120 = null, endBpm = null, meaningfulDrop = false, vo2max = null,
            fromPhone = true, strap = listOf(EffectFigures(2.1f, 0.1f, 16, 660)), effectFromStrap = true,
        )
        assertEquals("From the strap, which recorded this workout alongside the phone and worked these out itself.", s.rows.last().second)
    }

    @Test fun `a short workout after a long one says its own recovery and what's left from earlier`() {
        // A 6-minute walk 45 min after a long one: its own is 4 min, the long one left 16 h.
        assertEquals("Time to recover 4 min from this walk, 16 h in all with what's left from earlier", SummaryCopy.timeToRecover(978, 4, 3))
        assertEquals("Time to recover nothing from this walk, 16 h left from earlier", SummaryCopy.timeToRecover(978, 0, 3))
        // Its own close to the total, or not known: the total alone.
        assertEquals("Time to recover 17 h", SummaryCopy.timeToRecover(1022, 1000, 3))
        assertEquals("Time to recover 17 h", SummaryCopy.timeToRecover(1022, null, 3))
        val sheet = WorkoutDetailCopy.effectSheet(
            aerobic = 0.1f, anaerobic = null, load = 1, usualLoad = null, recoveryMinutes = 978,
            drop60 = null, drop120 = null, endBpm = null, meaningfulDrop = false, vo2max = null,
            fromPhone = true, strap = emptyList(), ownRecoveryMinutes = 4, sportType = 3,
        )
        assertTrue(sheet.rows.first { it.first == "Time to recover" }.second.endsWith("This workout: 4 min from this walk, 16 h in all with what's left from earlier."))
    }

    @Test fun `a long workout that held two strap records names both`() {
        val s = WorkoutDetailCopy.effectSheet(
            aerobic = null, anaerobic = null, load = 60, usualLoad = null, recoveryMinutes = null,
            drop60 = null, drop120 = null, endBpm = null, meaningfulDrop = false, vo2max = null,
            fromPhone = true, strap = listOf(EffectFigures(2.1f, null, 20, null, "18:05"), EffectFigures(1.0f, null, 16, null, "19:10")),
        )
        assertTrue(s.rows.last().second.endsWith(
            " The strap recorded it as 2 workouts of its own. From 18:05: aerobic 2.1, Load 20. From 19:10: aerobic 1.0, Load 16.",
        ))
    }

    @Test fun `nothing on the page uses an interpunct, semicolon or em dash`() {
        val lines = listOf(
            WorkoutDetailCopy.sentence(run, hour, 150, 100f, listOf(0, 0, 0, 900, 0)),
            WorkoutDetailCopy.sentence(strength, 60L, null, null, emptyList()),
            WorkoutDetailCopy.dropLine(30, true, 20)!!,
        ) + WorkoutDetailCopy.facts(5210f, true, 600, 5000, 300).flatMap { listOfNotNull(it.name, it.value, it.status) } +
            WorkoutDetailCopy.effectSheet(3.2f, 1.1f, 85, 70f, 840, 12, 20, 110, false, 48f, true, listOf(EffectFigures(3f, 1f, 80, 600, "21:40"), EffectFigures(2f, null, 16, null, "22:53")))
                .let { s -> listOfNotNull(s.info.name, s.info.meaning, s.hereLine) + s.info.scale + s.rows.flatMap { listOf(it.first, it.second) } }
        lines.forEach { l -> listOf("·", ";", "—").forEach { assertFalse("$l has $it", l.contains(it)) } }
    }

    @Test fun `a kcal target is a fact, reached at its time or not reached with how far it got`() {
        assertEquals(WorkoutFact("Target", "300 kcal", "Reached at 24:40", WorkoutFact.Kind.Target), WorkoutDetailCopy.targetFact("k:300", 1480, 340, Locale.UK))
        assertEquals(WorkoutFact("Target", "300 kcal", "Not reached, 212 of 300", WorkoutFact.Kind.Target), WorkoutDetailCopy.targetFact("k:300", null, 212, Locale.UK))
        assertEquals(WorkoutFact("Target", "1,200 kcal", "Reached", WorkoutFact.Kind.Target), WorkoutDetailCopy.targetFact("k:1200", null, 1250, Locale.UK))
        assertEquals("Not reached", WorkoutDetailCopy.targetFact("k:300", null, null, Locale.UK)!!.status)
        // Other targets say nothing here.
        assertNull(WorkoutDetailCopy.targetFact("t:1800", 1800, 400))
        assertNull(WorkoutDetailCopy.targetFact(null, null, 400))
        listOf(WorkoutDetailCopy.targetFact("k:300", 1480, 340)!!, WorkoutDetailCopy.targetFact("k:300", null, 212)!!)
            .flatMap { listOfNotNull(it.name, it.value, it.status) }
            .forEach { l -> listOf("·", ";", "—").forEach { b -> assertFalse("$l has $b", l.contains(b)) } }
    }
}
