package org.openstrap.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.openstrap.app.testing.LocaleRule

/** In English: the German words are in [org.openstrap.app.presentation.DataCopyTest]. */
class CalorieGoalTest {
    @get:Rule val locale = LocaleRule()

    @Test fun `the buttons step by 10 and stop at the strap's range`() {
        assertEquals(460, CalorieGoal.step(450, Goals.CALORIES_STEP))
        assertEquals(440, CalorieGoal.step(450, -Goals.CALORIES_STEP))
        assertEquals(465, CalorieGoal.step(455, Goals.CALORIES_STEP))
        assertEquals(200, CalorieGoal.step(205, -Goals.CALORIES_STEP))
        assertEquals(200, CalorieGoal.step(200, -Goals.CALORIES_STEP))
        assertEquals(3000, CalorieGoal.step(2995, Goals.CALORIES_STEP))
    }

    @Test fun `a typed goal is kept as typed when it's in range`() {
        assertEquals(455, CalorieGoal.parse("455"))
        assertEquals(1200, CalorieGoal.parse("1,200"))
        assertEquals(200, CalorieGoal.parse(" 200 "))
        assertEquals(3000, CalorieGoal.parse("3000"))
        assertNull(CalorieGoal.parse("199"))
        assertNull(CalorieGoal.parse("3001"))
        assertNull(CalorieGoal.parse(""))
        assertNull(CalorieGoal.parse("abc"))
        assertEquals("Pick a number from 200 to 3,000", CalorieGoal.OUT_OF_RANGE)
    }

    @Test fun `the suggestion is the median of the full days plus a tenth, rounded to 10`() {
        // Median of seven days is 400: 440.
        assertEquals(440, CalorieGoal.suggested(listOf(380f, 520f, 400f, 300f, 610f, 390f, 405f)))
        // Even count: median of 400 and 410 is 405, 445.5 rounds to 450.
        assertEquals(450, CalorieGoal.suggested(listOf(400f, 410f, 300f, 500f, 390f, 420f, 380f, 430f)))
        // Held to the strap's range.
        assertEquals(200, CalorieGoal.suggested(List(10) { 50f }))
        assertEquals(3000, CalorieGoal.suggested(List(10) { 4000f }))
    }

    @Test fun `under a week of full days suggests nothing`() {
        assertNull(CalorieGoal.suggested(emptyList()))
        assertNull(CalorieGoal.suggested(List(6) { 900f }))
        assertEquals(990, CalorieGoal.suggested(List(7) { 900f }))
    }

    @Test fun `the summary names the calorie goal`() {
        assertEquals("8,000 steps, 450 active kcal, 8 h sleep, 10 active hours", Goals(calories = 450).summary())
        assertEquals(300, Goals().calories)
    }
}
