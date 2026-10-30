package org.openstrap.app.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.device.WidgetUpdater

/**
 * Widgets spec 5: the strap status line's wordings, its button and words in the title row, and that every widget
 * still fits with it showing, the row counted at the button's 32 dp, at the three sizes' minimums and on One UI's
 * cells, at the fonts phones use and the largest, no gap over 32 dp on One UI.
 */
class WidgetStatusLineTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 24) // a Saturday
    private val now: Long = today.atTime(12, 0).atZone(zone).toEpochSecond()

    private fun line(s: StrapStatus) = StatusLine.of(s, now, zone, Locale.UK)

    /** Every state, the longest wording of each: "Synced Wednesday" is the widest the line gets. */
    private val lines = listOf(
        line(StrapStatus.NotPaired),
        line(StrapStatus.LowBattery(12)),
        line(StrapStatus.StaleSync(now - 3 * 3600 - 60)),
        line(StrapStatus.StaleSync(now - 86_400)),
        line(StrapStatus.StaleSync(now - 3 * 86_400)),
        line(StrapStatus.StaleSync(now - 11 * 86_400)),
        line(StrapStatus.Syncing),
    )

    private val scales = listOf(1f, 1.08f, 1.3f)

    /** Content sizes inside the padding: the three minimums and One UI's cells (spec 2.1). */
    private val minimum = Triple(110f to 110f, 260f to 120f, 260f to 220f)
    private val oneUi = Triple(140f to 180f, 313f to 180f, 313f to 290f)
    private val target = Spread.TARGET_GAP_DP + 0.01f

    /**
     * How far Start's Small button may sit from Steps' at the same cell, dp. Both rows sit half a gap from the top,
     * and each widget's gap is its own spare height shared out, so they differ by a few dp: 5 at most, on a One UI
     * 2 x 2 at font 1.3, under 2 elsewhere.
     */
    private val BUTTON_TOP_SLACK = 6f

    /* --------------------------------------------- The line itself --------------------------------------------- */

    @Test fun `each state's wordings, longest first, the line's own copy first`() {
        assertEquals(listOf("Pair your strap", "Pair strap"), line(StrapStatus.NotPaired).options)
        assertEquals(listOf("Strap at 12%", "12%"), line(StrapStatus.LowBattery(12)).options)
        assertEquals(listOf("Syncing"), line(StrapStatus.Syncing).options)
        assertEquals(listOf("Synced 08:59", "08:59"), line(StrapStatus.StaleSync(now - 3 * 3600 - 60)).options)
        assertEquals(listOf("Synced yesterday", "Yesterday"), line(StrapStatus.StaleSync(now - 86_400)).options)
        assertEquals(listOf("Synced Wednesday", "Synced Wed", "Wednesday", "Wed"), line(StrapStatus.StaleSync(now - 3 * 86_400)).options)
        assertEquals(listOf("Synced 13 Oct", "13 Oct"), line(StrapStatus.StaleSync(now - 11 * 86_400)).options)
        lines.forEach { l ->
            assertEquals(strapStatusLine(l.status, now, zone, Locale.UK), l.full)
            l.options.forEach { assertFalse(it, it.any { c -> c in "·;—–" }) }
        }
    }

    @Test fun `the words take a shorter wording before they drop, the button stays`() {
        val l = line(StrapStatus.StaleSync(now - 3 * 86_400))
        val full = WidgetText.fitWidth("Synced Wednesday", StatusButton.WORDS_SP, 1f)
        assertEquals("Synced Wednesday", StatusButton.words(l, full, 1f))
        assertEquals("Synced Wed", StatusButton.words(l, full - 1f, 1f))
        val tiny = WidgetText.fitWidth("Wed", StatusButton.WORDS_SP, 1f)
        assertEquals("Wed", StatusButton.words(l, tiny, 1f))
        assertNull(StatusButton.words(l, tiny - 1f, 1f))
        assertNull(StatusButton.words(null, 400f, 1f))
        // A Wide too narrow for any wording keeps the title and the button alone.
        val narrow = TopRow.of("Recovery", "This morning", l, 140f, 1f, withWords = true)
        assertEquals(l, narrow.status)
        assertNull(narrow.words)
        assertFalse(narrow.note)
    }

    @Test fun `the title stays, the button sits at the right and the words replace the note`() {
        val l = line(StrapStatus.StaleSync(now - 86_400))
        val wide = TopRow.of("Steps", "Goal 10,000", l, 313f, 1f, withWords = true)
        assertEquals(l, wide.status)
        assertEquals("Synced yesterday", wide.words)
        assertFalse("the words replace the note", wide.note)
        assertEquals(WidgetText.TITLE_SP, wide.titleSp, 0f)
        // The row counts the button's 32 dp, or the title's line when that's taller.
        assertEquals(StatusButton.SIZE_DP, wide.height, 0f)
        val big = TopRow.of("Steps", null, l, 313f, 2f, withWords = true)
        assertEquals(WidgetText.line(WidgetText.TITLE_SP, 2f), big.height, 1e-4f)
        // The words fit between the title and the button.
        val used = WidgetText.fitWidth("Steps", wide.titleSp, 1f) + StatusButton.TITLE_GAP_DP +
            WidgetText.fitWidth(wide.words, StatusButton.WORDS_SP, 1f) + StatusButton.WORDS_GAP_DP + StatusButton.SIZE_DP
        assertTrue(used <= 313f)
        // A Small has the button only.
        val small = TopRow.of("Steps", null, l, 110f, 1f, withWords = false)
        assertEquals(l, small.status)
        assertNull(small.words)
        // A long title steps down to fit beside the button rather than be cut.
        val tight = TopRow.of("Last night", null, l, 140f, 1.3f, withWords = false)
        assertTrue(tight.titleSp < WidgetText.TITLE_SP)
        assertTrue(WidgetText.fitWidth("Last night", tight.titleSp, 1.3f) + StatusButton.TITLE_GAP_DP + StatusButton.SIZE_DP <= 140f)
        // And one size down, no further, to make room for the words.
        val start = TopRow.of(StartWidgetModel.TITLE, null, line(StrapStatus.NotPaired), 313f, 1.3f, withWords = true)
        assertEquals(WidgetText.BODY_SP, start.titleSp, 0f)
        assertNotNull(start.words)
        // No status: the title's line, the note while it fits.
        val none = TopRow.of("Steps", "Goal 10,000", null, 313f, 1f, withWords = true)
        assertNull(none.status)
        assertTrue(none.note)
        assertEquals(WidgetText.line(WidgetText.TITLE_SP, 1f), none.height, 0f)
        // The tap reaches 44 dp, past the 32 dp circle on every side.
        assertTrue(StatusButton.TOUCH_DP >= 44f)
        assertTrue(StatusButton.TOUCH_DP > StatusButton.SIZE_DP)
    }

    @Test fun `a stale line has an alarm of its own, only while widgets are placed`() {
        assertEquals(now + 1, WidgetUpdater.staleAt(true, now - SYNC_STALE_SECONDS, now - 10))
        assertNull(WidgetUpdater.staleAt(false, now, now))
        assertNull(WidgetUpdater.staleAt(true, null, now))
        // Already stale: the line shows now, no alarm needed.
        assertNull(WidgetUpdater.staleAt(true, now - SYNC_STALE_SECONDS - 5, now))
    }

    @Test fun `the gaps share the spare height, half above and below`() {
        val s = Spread.of(20f, null, 0f, 30f, 10f)
        assertEquals(Spread(60f, 2), s)
        assertEquals(40f / 3, s.gap(100f), 1e-4f)
        assertEquals(20f / 3, s.edge(100f), 1e-4f)
        assertTrue(s.fits(68f, 4f))
        assertFalse(s.fits(67f, 4f))
    }

    /* ---------------------------------------------- Each widget ---------------------------------------------- */

    /** The title row's height at least the button's whenever the status shows: what every layout's sums now count. */
    private fun checkTop(where: String, top: TopRow, i: Int, w: Float, s: Float, l: StatusLine, real: Boolean) {
        assertEquals("$where: the button", l, top.status)
        assertTrue("$where: row ${top.height}", top.height >= StatusButton.SIZE_DP)
        if (i == 0) assertNull("$where: words on a Small", top.words)
        top.words?.let { assertTrue("$where: \"$it\" isn't one of the line's", it in l.options) }
        if (real && i > 0) assertNotNull("$where: no words", top.words)
        assertFalse("$where: the note stays", top.note)
    }

    /** Checks [fits] and, on One UI, [gap] at every line, font and size, and the top row ([checkTop]). */
    private fun every(
        what: String,
        check: (size: Int, w: Float, h: Float, s: Float, l: StatusLine) -> Triple<Boolean, Float, TopRow>,
    ) {
        for (l in lines) for (s in scales) for ((cells, real) in listOf(minimum to false, oneUi to true)) {
            listOf(cells.first, cells.second, cells.third).forEachIndexed { i, (w, h) ->
                val (fits, gap, top) = check(i, w, h, s, l)
                val where = "$what ${listOf("Small", "Wide", "Large")[i]} $w x $h at $s with \"${l.full}\""
                assertTrue("$where: doesn't fit", fits)
                checkTop(where, top, i, w, s, l, real)
                if (real) assertTrue("$where: gap $gap", gap <= target)
            }
        }
    }

    @Test fun `Recovery fits with the status line`() {
        val m = WidgetSamples.recovery(today, Locale.UK)
        every("Recovery") { i, w, h, s, l ->
            val f = when (i) {
                0 -> RecoveryLayout.small(m, w, h, s, l)
                1 -> RecoveryLayout.wide(m, w, h, s, l)
                else -> RecoveryLayout.large(m, w, h, s, l)
            }
            assertTrue(WidgetDots.width(m.score!!, f.dot) <= w)
            Triple(f.spread.fits(h, RecoveryLayout.MIN_GAP_DP), f.spread.gap(h), f.top)
        }
    }

    @Test fun `Steps fits with the status line`() {
        val m = WidgetSamples.steps(today, Locale.UK)
        every("Steps") { i, w, h, s, l ->
            if (i == 0) {
                val f = StepsLayout.small(m.stepsText, m.share, w, h, s, l)
                Triple(f.spread.fits(h, StepsLayout.SMALL_BAR_GAP_DP / 2), f.spread.gap(h), f.top)
            } else {
                val f = if (i == 1) StepsLayout.wide(m, w, h, s, l) else StepsLayout.large(m, w, h, s, l)
                Triple(f.spread.fits(h, StepsLayout.MIN_GAP_DP), f.spread.gap(h), f.top)
            }
        }
    }

    @Test fun `Sleep fits with the status line`() {
        val m = WidgetSamples.sleep(today, zone)
        every("Sleep") { i, w, h, s, l ->
            when (i) {
                0 -> SleepLayout.small(m, w, h, s, l).let { Triple(SleepLayout.smallHeight(it, s) <= h + 0.01f, it.spread.gap(h), it.top) }
                1 -> SleepLayout.wide(m, w, h, s, l).let { Triple(SleepLayout.wideHeight(it, s) <= h + 0.01f, it.spread.gap(h), it.top) }
                else -> SleepLayout.large(m, w, h, s, l).let {
                    Triple(SleepLayout.largeHeight(it, m.stages.size, s) <= h + 0.01f, SleepLayout.largeSpread(it, m.stages.size, s).gap(h), it.top)
                }
            }
        }
    }

    private val stepsModel = WidgetSamples.steps(today, Locale.UK)

    @Test fun `Start fits with the status line`() {
        val m = WidgetSamples.start(Instant.ofEpochSecond(now), zone, Locale.UK)
        val sizes = listOf(WidgetSizes.Small, WidgetSizes.Wide, WidgetSizes.Large)
        for (l in lines) for (s in scales) for ((cells, real) in listOf(minimum to false, oneUi to true)) {
            listOf(cells.first, cells.second, cells.third).forEachIndexed { i, (w, h) ->
                val where = "Start ${sizes[i]} $w x $h at $s with \"${l.full}\""
                if (i == 0) {
                    // As the other Smalls: the title row with the button, the tile with play beside it, the name.
                    val f = StartLayout.statusSmall(m.usual, l, w, h, s)
                    assertTrue("$where: ${f.spread.content} in $h", f.spread.fits(h, StartLayout.STATUS_MIN_GAP_DP))
                    checkTop(where, f.top, 0, w, s, l, real)
                    assertTrue("$where: play beside the tile", f.sizes.tile + StartLayout.STATUS_PLAY_GAP_DP + f.sizes.play <= w + 0.01f)
                    assertTrue("$where: the name", WidgetText.width(f.words.name, WidgetText.BODY_SP, s) <= w)
                    // The button where Steps' Small has it: the same right edge (both rows span the content) and top.
                    val steps = StepsLayout.small(stepsModel.stepsText, stepsModel.share, w, h, s, l)
                    val stepsTop = steps.spread.edge(h) + (steps.top.height - StatusButton.SIZE_DP) / 2
                    val startTop = StartLayout.statusSmallButtonTop(f, h)
                    assertEquals("$where: the button's top against Steps'", stepsTop, startTop, BUTTON_TOP_SLACK)
                    if (real) {
                        assertNotNull("$where: the title", f.title)
                        assertTrue("$where: gap ${f.spread.gap(h)}", f.spread.gap(h) <= target)
                        assertTrue("$where: shrunk", f.sizes.tile >= StartLayout.TILE_DP)
                    }
                    return@forEachIndexed
                }
                val top = StartLayout.statusTop(m, l, sizes[i], w, h, s)
                val b = if (i == 1) StartLayout.wide(m, w, h, s, top?.height ?: 0f) else StartLayout.large(m, w, h, s, top?.height ?: 0f)
                val least = (b.gaps - 2) * StartLayout.MIN_GAP_DP
                assertTrue("$where: ${b.content + least} in $h", b.content + least <= h + 0.01f)
                if (real) {
                    assertNotNull("$where: the line dropped", top)
                    checkTop(where, top!!, i, w, s, l, real)
                    assertTrue("$where: gap ${b.gap(h)}", b.gap(h) <= target)
                    assertNotNull("$where: the tiles", b.tiles)
                }
            }
        }
    }

    @Test fun `Moment fits with the status line`() {
        val m = WidgetSamples.moment(today, zone)
        every("Moment") { i, w, h, s, l ->
            val f = when (i) {
                0 -> MomentLayout.small(m, w, h, s, l)
                1 -> MomentLayout.wide(m, w, h, s, l)
                else -> MomentLayout.large(m, w, h, s, l)
            }
            assertNotNull(f.top)
            assertEquals(f.top!!.titleSp, f.name.sp, 0f)
            Triple(f.fits(h), f.gap(h), f.top)
        }
    }

    @Test fun `the samples are each widget's real look, in the copy rules`() {
        assertEquals("78", WidgetSamples.recovery(today).score)
        assertEquals("8,432", WidgetSamples.steps(today).stepsText)
        assertEquals("82", WidgetSamples.sleep(today, zone).score)
        assertEquals("Outdoor run", WidgetSamples.start(Instant.ofEpochSecond(now), zone, Locale.UK).usual.name)
        val moment = WidgetSamples.moment(today, zone)
        assertEquals("62", moment.number)
        val texts = listOfNotNull(moment.name, moment.number) + moment.sentences + moment.figures + moment.words +
            WidgetSamples.recovery(today).let { listOfNotNull(it.title, it.word, it.sentence, it.note) } +
            WidgetSamples.steps(today).let { listOf(it.title, it.word, it.toGo, it.share, it.weekLine) }
        texts.forEach { assertFalse(it, it.any { c -> c in "·;—" }) }
    }
}
