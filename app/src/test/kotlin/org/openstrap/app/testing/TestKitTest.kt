package org.openstrap.app.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** The shared test helpers do what their KDoc says. */
class TestKitTest {
    @get:Rule val locale = LocaleRule(Locale.GERMANY)

    @Test
    fun localeRuleSetsTheDefault() = assertEquals("1,5", "%.1f".format(1.5))

    @Test
    fun rulesPutTheDefaultsBack() {
        val zoneBefore = TimeZone.getDefault()
        val localeBefore = Locale.getDefault()
        var inside: Pair<String, Locale>? = null
        val body = object : Statement() {
            override fun evaluate() {
                inside = TimeZone.getDefault().id to Locale.getDefault()
            }
        }
        ZoneRule(ZoneId.of("Asia/Tokyo")).apply(LocaleRule(Locale.FRANCE).apply(body, Description.EMPTY), Description.EMPTY).evaluate()
        assertEquals("Asia/Tokyo" to Locale.FRANCE, inside)
        assertEquals(zoneBefore, TimeZone.getDefault())
        assertEquals(localeBefore, Locale.getDefault())
    }

    @Test
    fun fixedClockMovesOnlyWhenAsked() {
        val c = FixedClock(Instant.parse("2026-03-29T23:30:00Z"), ZoneId.of("Europe/London"))
        assertEquals(LocalDate.of(2026, 3, 30), c.today)
        assertEquals(c.instant, Instant.now(c.clock))
        c.advance(Duration.ofHours(1))
        assertEquals(1_774_830_600L, c.now)
    }

    @Test
    fun cleanCopyBansThePunctuationRangesIncluded() {
        assertCleanCopy(listOf("Slept 22:10 to 06:40", "5 to 10 min", "Good, steady"))
        for (bad in listOf("Rest · 2 min", "Easy; slow", "Now — later", "Easy – later", "Slept 22:10–06:40", "5–10 min")) {
            assertThrows(AssertionError::class.java) { assertCleanCopy(bad) }
        }
    }

    @Test
    fun sourceTreeFindsTheModule() {
        assertTrue(File(SourceTree.kotlin, "OpenStrapApp.kt").isFile)
        assertTrue(File(SourceTree.schemas, "1.json").isFile)
    }

    @Test
    fun buildersFillTheRest() {
        val w = workout(start = 100, minutes = 2, averageHeartRate = 120)
        assertEquals(220L, w.end)
        assertEquals(120, w.exerciseSeconds)
        assertNull(w.calories)
        assertEquals(1_800L, appWorkout(minutes = 30).end)
        val n = night(3)
        assertTrue(n.valid)
        assertEquals(3 * 86_400L, n.day)
        assertEquals(1990, profile().birthYear)
    }
}
