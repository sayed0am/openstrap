package org.openstrap.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.ui.components.GrowMotion
import org.openstrap.app.ui.components.TabBarFade
import org.openstrap.app.ui.dashboard.openBeside
import org.openstrap.app.data.Moment
import org.openstrap.app.ui.components.PinnedFade
import org.openstrap.app.ui.dashboard.ActivityCard
import org.openstrap.app.ui.dashboard.ActivityFacts
import org.openstrap.app.ui.dashboard.RecoveryFacts
import org.openstrap.app.ui.dashboard.SleepFacts
import org.openstrap.app.ui.dashboard.TabSlot
import org.openstrap.app.ui.dashboard.TodayCard
import org.openstrap.app.ui.dashboard.TodayFacts
import org.openstrap.app.ui.dashboard.activityCards
import org.openstrap.app.ui.dashboard.lookClashes
import org.openstrap.app.ui.dashboard.recoveryCards
import org.openstrap.app.ui.dashboard.sleepCards
import org.openstrap.app.ui.dashboard.tabClashes
import org.openstrap.app.ui.dashboard.tabPanes
import org.openstrap.app.ui.dashboard.todayCards
import org.openstrap.app.ui.dashboard.todayPanes
import org.openstrap.app.ui.dashboard.varyLooks
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.GlowGrid
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.Pane
import org.openstrap.app.ui.glow.valueBeside
import java.io.File

/**
 * The frame's rules (spec 2.4 Unfolded, 3.2, 3.5): the fade behind the tab bar, You and Strap growing from their
 * buttons, and the layout on an unfolded screen.
 */
class FrameRulesTest {
    private val root: File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(root, "kotlin/org/openstrap/app/$path").readText()

    @Test fun `the fade behind the tab bar is 120 dp, solid black for its lower 45 percent`() {
        assertEquals(120.dp, TabBarFade.Height)
        val stops = TabBarFade.stops
        assertEquals(0f to Color.Transparent, stops.first())
        assertEquals(1f to Color.Black, stops.last())
        // Black from 55% down: the lower 45% is solid.
        assertEquals(0.55f, stops.first { it.second == Color.Black }.first, 1e-4f)
    }

    @Test fun `the tab bar draws its fade`() {
        val bar = source("ui/components/Insights.kt")
        val body = bar.substringAfter("fun FloatingNavBar(").substringBefore("fun FloatingNavRail(")
        assertTrue("FloatingNavBar draws TabBarFade behind the pill", "TabBarFade.brush(" in body && "drawBehind" in body)
    }

    @Test fun `a page starts as its button's circle and ends as the page`() {
        val button = Rect(Offset(16f, 40f), androidx.compose.ui.geometry.Size(38f, 38f))
        val page = Rect(0f, 0f, 400f, 800f)
        val start = GrowMotion.outline(button, page, 0f)
        assertEquals(button, Rect(start.left, start.top, start.right, start.bottom))
        assertEquals(19f, start.topLeftCornerRadius.x, 1e-4f)
        val end = GrowMotion.outline(button, page, 1f)
        assertEquals(page, Rect(end.left, end.top, end.right, end.bottom))
        assertEquals(0f, end.topLeftCornerRadius.x, 1e-4f)
        // A pill (the strap chip) is round at its ends: half its height.
        val chip = Rect(300f, 46f, 380f, 72f)
        assertEquals(13f, GrowMotion.outline(chip, page, 0f).topLeftCornerRadius.x, 1e-4f)
    }

    @Test fun `it grows at 500 and shrinks at 300, the surface at once and the content once it has some size`() {
        assertEquals(500f, GrowMotion.stiffness(opening = true))
        assertEquals(300f, GrowMotion.stiffness(opening = false))
        assertEquals(0f, GrowMotion.alpha(0f))
        assertEquals(1f, GrowMotion.alpha(GrowMotion.SURFACE_PART))
        assertEquals(0f, GrowMotion.contentAlpha(GrowMotion.CONTENT_FROM))
        assertEquals(1f, GrowMotion.contentAlpha(0.5f))
        assertEquals(1f, GrowMotion.contentAlpha(1f))
    }

    @Test fun `the page keeps a card's corners until the end, its content scaled to the outline`() {
        val button = Rect(Offset(16f, 40f), androidx.compose.ui.geometry.Size(38f, 38f))
        val page = Rect(0f, 0f, 400f, 800f)
        assertEquals(28f, GrowMotion.outline(button, page, GrowMotion.SQUARE_FROM, 28f).topLeftCornerRadius.x, 1e-3f)
        assertEquals(0f, GrowMotion.outline(button, page, 1f, 28f).topLeftCornerRadius.x, 1e-3f)
        assertEquals(0.5f, GrowMotion.contentScale(200f, 400f), 1e-4f)
        // The tab behind: dimmed by half and at 94% with the page open, untouched with it closed.
        assertEquals(0.5f, GrowMotion.scrim(1f), 1e-4f)
        assertEquals(0.94f, GrowMotion.sink(1f), 1e-4f)
        assertEquals(1f, GrowMotion.sink(0f), 1e-4f)
    }

    @Test fun `You and Strap grow from the header's buttons`() {
        val header = source("ui/dashboard/MomentHeader.kt")
        assertTrue("the avatar records itself", Regex("""growClick\(onOpenYou\)""").containsMatchIn(header))
        assertTrue("the strap chip records itself", Regex("""growClick\(onOpenStrap\)""").containsMatchIn(header))
        val root = source("MainActivity.kt")
        assertTrue("the pages over the tab grow from the origin", "GrowFromButton(" in root && "growOrigin.take()" in root)
    }

    @Test fun `tabs keep their phone gutters folded, and unfolded are at most 960 dp with a 24 dp gutter`() {
        assertEquals(20.dp, GlowLayout.dashboardGutter(411.dp))
        assertEquals(16.dp, GlowLayout.dashboardGutter(375.dp))
        assertEquals(24.dp, GlowLayout.dashboardGutter(750.dp))
        // A tablet: the content is 960 dp, centred.
        assertEquals(120.dp, GlowLayout.dashboardGutter(1200.dp))
        assertEquals(960.dp, GlowLayout.MaxContentWidth)
        assertEquals(12.dp, GlowLayout.ColumnGap)
    }

    @Test fun `a page keeps to one centred column of at most 600 dp, and a phone's page is unchanged`() {
        assertEquals(600.dp, GlowLayout.PageMaxWidth)
        assertEquals(20.dp, GlowLayout.pageSide(411.dp, GlowLayout.PageMaxWidth, GlowLayout.Gutter))
        val side = GlowLayout.pageSide(750.dp, GlowLayout.PageMaxWidth, GlowLayout.Gutter)
        assertEquals(560.dp, 750.dp - side * 2)
        // A dashboard clears the column and lays out its own width.
        assertEquals(16.dp, GlowLayout.pageSide(750.dp, androidx.compose.ui.unit.Dp.Unspecified, 16.dp))
    }

    @Test fun `the last block of a column that ends far below the other moves to the shorter one`() {
        // Left: What drove it, Body signals, Habit Age; right: one card. Habit Age moves right.
        assertEquals(listOf(0, 1) to listOf(3, 2), GlowLayout.balanceColumns(listOf(400, 300, 200), listOf(300), gap = 12))
        // The right column longer: its last moves left.
        assertEquals(listOf(0, 3) to listOf(1, 2), GlowLayout.balanceColumns(listOf(300), listOf(400, 300, 200), gap = 12))
    }

    @Test fun `columns stay as they are when a move wouldn't bring their ends closer`() {
        // Moving the 500 would leave the right far longer.
        assertEquals(listOf(0, 1) to listOf(2), GlowLayout.balanceColumns(listOf(100, 500), listOf(400), gap = 12))
        // A column of one block never empties.
        assertEquals(listOf(0) to listOf(1), GlowLayout.balanceColumns(listOf(900), listOf(100), gap = 12))
        // Even columns stay.
        assertEquals(listOf(0, 1) to listOf(2, 3), GlowLayout.balanceColumns(listOf(200, 200), listOf(200, 200), gap = 12))
    }

    @Test fun `a row on the You or Strap page opens its page beside the list, in place of the one there`() {
        assertEquals(listOf("Strap", "Alarms"), openBeside(listOf("Strap"), "Alarms"))
        assertEquals(listOf("You", "Diagnostics"), openBeside(listOf("You", "Profile"), "Diagnostics"))
        assertEquals(listOf("Strap", "Alarms"), openBeside(listOf("Strap", "Firmware", "ZeppAccount"), "Alarms"))
    }

    @Test fun `the sync line under the header shows one line at a time`() {
        // Audit section 5: the pull hint drew over "Syncing strap…". The new line fades in only once the old is out.
        val reveal = source("ui/components/PullToSync.kt").substringAfter("fun PullSyncReveal(")
        assertTrue("the label fades out, then in", "fadeIn(tween(LABEL_FADE_MS, delayMillis = LABEL_FADE_MS)) togetherWith fadeOut(tween(LABEL_FADE_MS))" in reveal)
    }

    @Test fun `sheets, workout pages, the Zepp sign-in and Profile's Save keep to the page column`() {
        val glass = source("ui/components/Glass.kt").substringAfter("fun GlassSheet(")
        assertTrue("GlassSheet is at most PageMaxWidth", "widthIn(max = org.openstrap.app.ui.glow.GlowLayout.PageMaxWidth)" in glass)
        val workout = source("ui/glow/GlowWorkoutMode.kt").substringAfter("fun WorkoutPage(").substringBefore("\n}\n")
        assertTrue("WorkoutPage centres its content and its pinned buttons", Regex("""pageSide\(""").findAll(workout).count() == 1 && "start = side, end = side" in workout)
        val profile = source("ui/device/ProfileScreen.kt")
        assertTrue("Profile's Save is as wide as the fields", "start = side, end = side, bottom = bottom + 12.dp" in profile && "widthIn(max = 600.dp)" !in profile)
        val zepp = source("ui/zepp/ZeppSignIn.kt").substringAfter("fun ZeppSignInDialog(")
        assertTrue("the Zepp sign-in keeps to the page column", "Column(Modifier.widthIn(max = GlowLayout.PageMaxWidth)" in zepp && "contentAlignment = Alignment.TopCenter" in zepp)
        val screens = source("ui/dashboard/ScreenParts.kt")
        assertTrue("ScreenList centres a page", "LocalPageColumn.current" in screens)
    }

    /* ------------------------------ Smalls never narrower than folded (spec 2.4) ------------------------------ */

    @Test fun `a Small is never narrower than two across on a folded phone`() {
        // Folded (411 dp, 20 dp gutters): about 180 dp each, the narrowest a Small gets.
        assertTrue(GlowGrid.unitWidthIn(411.dp - 40.dp) >= GlowLayout.SmallMinWidth)
        assertTrue(GlowLayout.SmallMinWidth >= 176.dp)
        // The Fold's inner screen: a 345 dp column would make each Small 166 dp, so a row of Smalls spans both columns.
        assertTrue(GlowLayout.smallsAcross(750.dp))
        assertTrue(GlowLayout.smallsAcross(GlowLayout.TwoColumnWidth))
        // A phone has one column, and a tablet's 474 dp columns hold two Smalls of 231 dp.
        assertTrue(!GlowLayout.smallsAcross(411.dp))
        assertTrue(!GlowLayout.smallsAcross(1200.dp))
        // Wherever Smalls stay in a column, each is at least the folded width.
        for (w in 600..1400 step 10) {
            val width = w.dp
            if (width >= GlowLayout.TwoColumnWidth && !GlowLayout.smallsAcross(width)) {
                val column = (width - GlowLayout.dashboardGutter(width) * 2 - GlowLayout.ColumnGap) / 2
                assertTrue("Smalls at $w dp", GlowGrid.unitWidthIn(column) >= GlowLayout.SmallMinWidth)
            }
        }
    }

    private fun <C> assertSmallsAcross(slots: List<TabSlot<C>>, what: String) {
        val panes = tabPanes(slots, smallsAcross = true)
        slots.forEachIndexed { i, s ->
            if (s.size == CardSize.Small || s.size == CardSize.Tall) assertEquals("$what ${s.card} spans both columns", Pane.Full, panes[i])
            else if (s.pane == Pane.Full) assertEquals(Pane.Full, panes[i])
            else assertEquals("$what ${s.card} keeps its column", s.pane, panes[i])
        }
        val clash = tabClashes(slots, wide = true, smallsAcross = true).firstOrNull()
        assertTrue("$what: ${clash?.let { slots[it.first].card to slots[it.second].card }} look the same", clash == null)
    }

    @Test fun `unfolded, the tabs' rows of Smalls span both columns and stay varied`() {
        val bools = listOf(false, true)
        for (today in bools) for (suggestion in bools) for (live in bools) {
            assertSmallsAcross(activityCards(ActivityFacts(today, suggestion, live)), "activity")
        }
        for (habit in bools) assertSmallsAcross(recoveryCards(RecoveryFacts(habitAge = habit)), "recovery")
        for (breathing in bools) for (naps in bools) assertSmallsAcross(sleepCards(SleepFacts(breathing, naps)), "sleep")
        // Activity: Active minutes and Heart points two across on their own row. Heart rate isn't beside them but
        // in its column under it, beside Load, so the columns are still there to balance.
        val act = activityCards()
        val panes = tabPanes(act, smallsAcross = true)
        fun pane(c: ActivityCard) = panes[act.indexOfFirst { it.card == c }]
        assertEquals(Pane.Full, pane(ActivityCard.ActiveMinutes))
        assertEquals(Pane.Full, pane(ActivityCard.HeartPoints))
        assertEquals(Pane.Left, pane(ActivityCard.Load))
        assertEquals(Pane.Right, pane(ActivityCard.HeartRate))
        // With room in each column (a tablet) the pair keeps its column as before.
        assertEquals(act.map { it.pane }, tabPanes(act, smallsAcross = false))
    }

    @Test fun `unfolded, Today's Small pair after a workout spans both columns and stays varied`() {
        for (m in Moment.entries) for (workout in listOf(false, true)) {
            val slots = varyLooks(todayCards(m, TodayFacts(workout = workout)), m, wide = true, smallsAcross = true)
            val panes = todayPanes(slots, smallsAcross = true)
            slots.forEachIndexed { i, s -> if (s.size == CardSize.Small || s.size == CardSize.Tall) assertEquals("$m ${s.card}", Pane.Full, panes[i]) }
            assertTrue("$m workout=$workout varied", lookClashes(slots, wide = true, moment = m, smallsAcross = true).isEmpty())
        }
        val after = varyLooks(todayCards(Moment.After, TodayFacts(workout = true)), Moment.After, wide = true, smallsAcross = true)
        assertTrue(after.any { it.card == TodayCard.LoadToday && it.size == CardSize.Small })
    }

    @Test fun `the dashboards decide from their width whether Smalls span`() {
        val layout = source("ui/glow/GlowLayout.kt")
        assertTrue("GlowScreenList tells its blocks", "GlowBlocks(smallsAcross = GlowLayout.smallsAcross(maxWidth))" in layout)
        val tabs = source("ui/glow/TabParts.kt").substringAfter("fun <C> GlowBlocks.tabCards(").substringBefore("\n}\n")
        assertTrue("tabCards places rows by tabPanes", "tabPanes(slots, smallsAcross)" in tabs)
        val today = source("ui/glow/GlowTodayScreen.kt")
        assertTrue("Today spans its Small pair too", "GlowLayout.smallsAcross(maxWidth)" in today && "todayPanes(slots, across)" in today)
    }

    /* ------------------------------------ A row name on one line (spec 2.3) ----------------------------------- */

    @Test fun `a row's value sits beside its name only when both fit on one line`() {
        // Folded: "Phone alerts" and "Calls and notifications" fit side by side.
        assertTrue(valueBeside(nameWidth = 85, valueWidth = 140, available = 260, gap = 12))
        // Beside a pane the list narrows: the value drops under the name instead of wrapping it.
        assertTrue(!valueBeside(nameWidth = 85, valueWidth = 140, available = 200, gap = 12))
        assertTrue(valueBeside(nameWidth = 85, valueWidth = 103, available = 200, gap = 12))
    }

    @Test fun `row names keep to one line in every list row`() {
        val parts = source("ui/glow/PageParts.kt")
        val name = parts.substringAfter("internal fun RowName(").substringBefore("\n\n")
        assertTrue("RowName is one line", "maxLines = 1" in name && "TextOverflow.Ellipsis" in name)
        val row = parts.substringAfter("fun PageRow(").substringBefore("\n}\n")
        assertTrue("PageRow lays out name and value with RowNameValue", "RowNameValue(" in row && "RowName(" in row)
        assertTrue("PageRow no longer squeezes its value beside the name", "widthIn(max = 160.dp)" !in row)
        assertTrue("PageSwitchRow keeps its name on one line", "RowName(title)" in parts.substringAfter("fun PageSwitchRow("))
        assertTrue("ListRow keeps its name on one line", "RowName(name)" in source("ui/glow/GlowList.kt").substringAfter("fun ListRow("))
        assertTrue("SettingRow keeps its name on one line", "RowName(title)" in source("ui/glow/ReadyScreen.kt").substringAfter("fun SettingRow("))
    }

    /* --------------------------------- A fade under pinned buttons (spec 3.2) --------------------------------- */

    @Test fun `the fade under pinned buttons has the tab bar fade's ramp, solid below the buttons`() {
        assertEquals(66f, PinnedFade.Ramp.value, 1e-3f)
        // The box from the buttons' top (y 0) to the screen's bottom (y 200), the ramp 66 above it.
        val stops = PinnedFade.stops(top = -66f, solidFrom = 0f, bottom = 200f)
        assertEquals(0f to Color.Transparent, stops.first())
        assertEquals(66f / 266f, stops[1].first, 1e-4f)
        assertEquals(Color.Black, stops[1].second)
        assertEquals(1f to Color.Black, stops.last())
        // A list's last content ends the ramp above the buttons.
        assertEquals(100.dp + 66.dp - 24.dp, PinnedFade.listClearance(100.dp, listEnd = 24.dp))
    }

    @Test fun `every pinned button has the fade behind it and the page ends clear of it`() {
        val profile = source("ui/device/ProfileScreen.kt")
        assertTrue("Profile's Save sits in a box with the fade", Regex("""Box\(Modifier\.align\(Alignment\.BottomCenter\)\.fillMaxWidth\(\)\.pinnedFade\(\)""").containsMatchIn(profile))
        assertTrue("Profile's list ends clear of Save and its fade", "PinnedFade.listClearance(bottom + 12.dp + SaveHeight, listEnd = 24.dp)" in profile)
        val workout = source("ui/glow/GlowWorkoutMode.kt").substringAfter("fun WorkoutPage(").substringBefore("\n}\n")
        assertTrue("WorkoutPage's pinned buttons (Start, Done, Choose the sport) have the fade", ".pinnedFade()" in workout)
        assertTrue("WorkoutPage's content ends clear of them", "PinnedFade.listClearance(pinnedHeight)" in workout)
        assertTrue("WorkoutPage's old short fade is gone", "0.25f to Color.Black" !in workout)
        // Every page with pinned buttons uses WorkoutPage's or Profile's.
        for (f in listOf("ui/glow/GlowWorkoutMode.kt", "ui/glow/ReadyScreen.kt", "ui/glow/ChooseScreen.kt", "ui/glow/GlowWorkoutSummary.kt")) {
            val src = source(f)
            assertTrue("$f pins its buttons through WorkoutPage", "pinned = {" !in src || "WorkoutPage(" in src)
        }
    }
}
