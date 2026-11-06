package org.openstrap.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.glow.pickedType
import java.io.File
import org.openstrap.app.presentation.WorkoutCopy

/**
 * The workout pages' rules (spec 6.3, design step 9): twelve everyday sports and More sports, outdoor or indoor as a
 * switch on Ready that picks the saved sport type and GPS, and the pages in the design system's type.
 */
class WorkoutRulesTest {
    @Test fun `the picker leads with the twelve everyday sports`() {
        assertEquals(
            // Hike isn't on the strap for now (Workouts.NOT_ON_STRAP): Cross training has its place.
            listOf("Run", "Walk", "Cross-training", "Cycling", "Swim", "Strength", "HIIT", "Yoga", "Pilates", "Rowing", "Elliptical", "Other"),
            Workouts.EVERYDAY.map(Workouts::shortName),
        )
        // Other is free training.
        assertEquals("Free training", Workouts.sportName(Workouts.EVERYDAY.last()))
    }

    @Test fun `indoor and outdoor are one sport in the picker`() {
        val indoorKinds = Workouts.SIDES.map { it.indoor }.filter { it !in Workouts.SIDES.map { s -> s.outdoor } }
        // Open water and indoor walk aren't on the strap for now, so Swim and Walk have one kind.
        assertEquals(listOf(2, 8 /* , 6, 40 */), indoorKinds)
        indoorKinds.forEach { assertFalse("$it has a tile", it in Workouts.EVERYDAY || it in Workouts.MORE) }
        // Run, Cycling, Swim and Walk each have one tile, and it starts the sport's key (Swim the pool for now).
        listOf(1, 4, 6, 3).forEach { assertTrue(it in Workouts.EVERYDAY) }
        assertEquals("Run", Workouts.shortName(2))
        assertEquals("Swim", Workouts.shortName(6))
        assertEquals("Walk", Workouts.shortName(40))
    }

    @Test fun `no sport type is removed, they move behind More sports`() {
        val offered = Workouts.sports.map { it.first }
        val reachable = Workouts.EVERYDAY + Workouts.MORE + Workouts.SIDES.flatMap { listOf(it.outdoor, it.indoor) }
        offered.forEach { assertTrue("${Workouts.sportName(it)} can't be picked", it in reachable) }
        // More sports is every other sport, once each, A to Z.
        assertEquals(Workouts.MORE.distinct(), Workouts.MORE)
        assertEquals(Workouts.MORE.sortedBy { Workouts.sportName(it) }, Workouts.MORE)
        assertTrue(Workouts.MORE.none { it in Workouts.EVERYDAY })
        assertTrue(17 in Workouts.MORE) // Tennis
    }

    @Test fun `every sport offered is one the strap runs a session for`() {
        // The strap's own list (0x004D op 04, Helio Strap fw 3.25, read 2026-11-06), the one Zepp's picker shows.
        val onStrap = (1..6) + listOf(8, 9, 17, 21, 23, 24) + (49..55) + (57..61) + (71..81) + (85..89) + listOf(91, 92, 93) +
            (95..104) + listOf(109) + (111..120) + listOf(122, 130) + (143..156) + (158..168) + listOf(170, 171) + (173..177) +
            (185..189) + listOf(191, 214, 216, 217, 222) + (226..228)
        val offered = Workouts.EVERYDAY + Workouts.MORE + Workouts.SIDES.flatMap { listOf(it.outdoor, it.indoor) }
        offered.forEach { assertTrue("${Workouts.sportName(it)} ($it) isn't on the strap", it in onStrap) }
        // Soccer, Padel and Pickleball start as the strap's ids.
        assertTrue(191 in Workouts.MORE && 216 in Workouts.MORE && 227 in Workouts.MORE)
        assertEquals(Workouts.sportName(18), Workouts.sportName(191))
    }

    @Test fun `the switch saves the strap's own sport type`() {
        fun sides(type: Int) = Workouts.sidesOf(type)!!.let { it.type(false) to it.type(true) }
        assertEquals(1 to 2, sides(1))
        assertEquals(1 to 2, sides(2))
        assertEquals(4 to 8, sides(4))
        // Not on the strap for now (Workouts.NOT_ON_STRAP): open water and indoor walk.
        // assertEquals(7 to 6, sides(6))
        // An indoor walk is the strap's own Indoor walk, so its name says so.
        // assertEquals(3 to 40, sides(3))
        // assertEquals(3 to 40, sides(40))
        assertNull(Workouts.sidesOf(6))
        assertNull(Workouts.sidesOf(3))
        assertEquals("Indoor walk", Workouts.sportName(40))
        assertNull(Workouts.sidesOf(15))
        assertNull(Workouts.sidesOf(52))
    }

    @Test fun `Ready opens on the side you last chose, or the kind you picked by name`() {
        val none = emptyMap<Int, Boolean>()
        assertEquals(false, Workouts.indoorSide(1, none))
        assertEquals(true, Workouts.indoorSide(1, mapOf(1 to true)))
        // Treadmill found by name is the indoor side, whatever you chose before.
        assertEquals(true, Workouts.indoorSide(2, mapOf(1 to false)))
        // Most swims are in a pool. Not on the strap for now: open water and indoor walk.
        // assertEquals(true, Workouts.indoorSide(7, none))
        // assertEquals(false, Workouts.indoorSide(7, mapOf(7 to false)))
        // assertEquals(true, Workouts.indoorSide(3, mapOf(3 to true)))
        assertNull(Workouts.indoorSide(3, mapOf(3 to true)))
        assertNull(Workouts.indoorSide(52, mapOf(1 to true)))
    }

    @Test fun `GPS goes with the outdoor side and sports done outdoors`() {
        assertTrue(Workouts.gpsOffered(1, indoor = false))
        assertFalse(Workouts.gpsOffered(1, indoor = true))
        assertFalse(Workouts.gpsOffered(2, indoor = true))
        assertTrue(Workouts.gpsOffered(3, indoor = false))
        // Walk has no indoor side for now, and open water isn't on the strap.
        // assertFalse(Workouts.gpsOffered(3, indoor = true))
        // assertTrue(Workouts.gpsOffered(7, indoor = false))
        assertTrue(3 in Workouts.OUTDOOR_ONLY)
        assertFalse(Workouts.gpsOffered(6, indoor = true))
        // A sport done only outdoors has GPS and the location mark; an indoor one has neither.
        assertTrue(Workouts.gpsOffered(15, indoor = null))
        assertTrue(15 in Workouts.OUTDOOR_ONLY && 11 in Workouts.OUTDOOR_ONLY && 12 in Workouts.OUTDOOR_ONLY)
        assertFalse(Workouts.gpsOffered(52, indoor = null))
        assertTrue(Workouts.OUTDOOR_ONLY.none { Workouts.sidesOf(it) != null })
    }

    @Test fun `a tile picked away from Ready takes the remembered or default side`() {
        assertEquals(1, pickedType(1, emptyMap()))
        assertEquals(2, pickedType(1, mapOf(1 to true)))
        // assertEquals(6, pickedType(7, emptyMap())) // open water isn't on the strap for now
        assertEquals(2, pickedType(2, emptyMap()))
        assertEquals(52, pickedType(52, mapOf(1 to true)))
    }

    @Test fun `the chosen sides survive being stored`() {
        val sides = mapOf(1 to true, 4 to false)
        assertEquals("1=1,4=0", Workouts.formatSides(sides))
        assertEquals(sides, Workouts.parseSides(Workouts.formatSides(sides)))
        assertEquals(emptyMap<Int, Boolean>(), Workouts.parseSides(null))
        // Unknown sports, an indoor kind as a key and broken parts are left out.
        assertEquals(mapOf(4 to false), Workouts.parseSides("4=0,2=1,52=1,x=1,4,7=yes"))
    }

    @Test fun `search looks through every sport`() {
        assertEquals(emptyList<Int>(), Workouts.search("  "))
        assertEquals(listOf(2), Workouts.search("tread"))
        // Open water swim and trail run aren't on the strap for now.
        assertEquals(listOf(6), Workouts.search("swim"))
        assertTrue(1 in Workouts.search("run") && 11 !in Workouts.search("run"))
        assertTrue(17 in Workouts.search("Tennis"))
        assertEquals(Workouts.search("yoga").distinct(), Workouts.search("yoga"))
    }

    @Test fun `Recent is your last four sports, by their tiles`() {
        assertEquals(listOf(1, 52, 6, 60), Workouts.recentSports(listOf(2, 52, 1, Workouts.GENERIC_ACTIVITY, 6, 60, 3)))
        // A sport not on the strap is left out, and an old id starts as the strap's.
        assertEquals(listOf(191, 216, 3), Workouts.recentSports(listOf(15, 18, 7, 205, 3)))
        assertEquals(emptyList<Int>(), Workouts.recentSports(listOf(Workouts.GENERIC_ACTIVITY)))
    }

    @Test fun `Start names the sport`() {
        assertEquals("Start run", WorkoutCopy.startLabel(2))
        assertEquals("Start HIIT", WorkoutCopy.startLabel(49))
        assertEquals("Start workout", WorkoutCopy.startLabel(5))
        assertEquals("Start tennis", WorkoutCopy.startLabel(17))
    }

    @Test fun `no workout text uses an interpunct, semicolon or em dash`() {
        val strings = Workouts.SIDES.flatMap { listOf(it.outdoorName, it.indoorName) } + Workouts.EVERYDAY.map(Workouts::shortName) +
            SummaryCopy.DROP_MEANING
        strings.forEach { s -> assertFalse("\"$s\"", s.any { it == '·' || it == ';' || it == '—' }) }
    }

    @Test fun `the workout pages use the type roles and no Material parts`() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val glow = File(root, "kotlin/org/openstrap/app/ui/glow")
        val files = listOf("GlowWorkoutMode.kt", "ChooseScreen.kt", "ReadyScreen.kt", "WorkoutCapsule.kt", "GlowWorkoutSummary.kt", "GlowWorkoutScreen.kt").map { File(glow, it) } +
            File(glow, "live").listFiles().orEmpty().filter { it.extension == "kt" }.sortedBy { it.name }
        val banned = Regex("""MaterialTheme\.typography|GlowType\.(headline|title|label)\b|OutlinedTextField|\bIconButton\b|headline\.fontSize""")
        val found = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (banned.containsMatchIn(code)) "${f.name}:${i + 1}  ${line.trim()}" else null
            }
        }
        if (found.isNotEmpty()) fail("Workout pages take their type from GlowType's roles (spec 2.3, 6.3):\n" + found.joinToString("\n"))
    }
}
