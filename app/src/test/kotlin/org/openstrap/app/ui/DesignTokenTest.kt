package org.openstrap.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.ui.glow.BarMaxWidth
import org.openstrap.app.ui.glow.DotSize
import androidx.compose.ui.graphics.luminance
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowPalette
import org.openstrap.app.ui.glow.GlowStyle
import org.openstrap.app.ui.theme.MetricColors
import java.io.File

/**
 * The design system's tokens (spec 2.2 to 2.4, 8.2): the values match the spec, and the UI takes them from their one
 * place. Scans the code under ui/, comments and strings left out, and fails with file and line.
 */
class DesignTokenTest {
    private val root: File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val uiRoot = File(root, "kotlin/org/openstrap/app/ui")

    /** Every Kotlin file under ui/, by its path from ui/, as code only. */
    private val sources: Map<String, List<String>> by lazy {
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
            .associate { it.relativeTo(uiRoot).invariantSeparatorsPath to codeOnly(it.readText()).lines() }
    }

    /** Each line that matches [pattern], as "path:line  text", skipping the files in [except]. */
    private fun find(pattern: Regex, except: Set<String> = emptySet()): List<String> = sources.flatMap { (path, lines) ->
        if (path in except) emptyList()
        else lines.mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "$path:${i + 1}  ${line.trim()}" else null }
    }

    @Test fun `the tokens hold the spec's values`() {
        assertEquals(listOf(2.4f, 3f, 3.6f, 4.5f, 6f, 9f), DotSize.entries.map { it.dot.value })
        DotSize.entries.forEach { assertEquals(it.name, it.dot.value * 0.38f, it.gap.value, 1e-4f) }
        assertEquals(28.dp, GlowLayout.CardCorner)
        assertEquals(16.dp, GlowLayout.TileCorner)
        assertEquals(GlowLayout.CardCorner, GlowStyle(Color.White).radius)
        val p = GlowLayout.CardPadding
        assertEquals(
            List(4) { 20.dp },
            listOf(p.calculateLeftPadding(LayoutDirection.Ltr), p.calculateTopPadding(), p.calculateRightPadding(LayoutDirection.Ltr), p.calculateBottomPadding()),
        )
        assertEquals(14.dp, GlowLayout.RowPadding)
        assertEquals(Color(0xFFF66C28), MetricColors.charge)
        assertEquals(Color(0xFFA274F2), MetricColors.evening)
        assertEquals(Color(0xFFFFD23F), MetricColors.calories)
        assertEquals(Color(0xFF8C7314), MetricColors.caloriesShade)
    }

    @Test fun `glow bodies keep white text readable, and are no darker than they need be`() {
        assertEquals(0.18f, GlowPalette.MID_MAX_LUMINANCE, 1e-6f)
        assertTrue(GlowPalette.TONE_MAX_LUMINANCE < GlowPalette.MID_MAX_LUMINANCE)
        // White on the body: at least 4.5:1 (spec 2.1), for every metric's ink.
        val inks = listOf(
            MetricColors.readiness, MetricColors.charge, MetricColors.sleepDeep, MetricColors.heart, MetricColors.steps,
            MetricColors.hrv, MetricColors.spo2, MetricColors.stress, MetricColors.temperature, MetricColors.load,
            MetricColors.habitAge, MetricColors.evening, MetricColors.calories,
        )
        inks.forEach { ink ->
            val body = GlowPalette(GlowStyle(ink)).mid.luminance()
            assertTrue("$ink body luminance $body", body <= GlowPalette.MID_MAX_LUMINANCE + 1e-3f)
            assertTrue("$ink white on body ${1.05f / (body + 0.05f)}", 1.05f / (body + 0.05f) >= 4.5f)
        }
        assertEquals(0.08f, GlowLayout.HairlineAlpha, 1e-6f)
        assertEquals(0.06f, GlowLayout.DividerAlpha, 1e-6f)
    }

    @Test fun `only the digits are made of dots`() {
        // No dotted or dashed line anywhere, and no dotted panels (spec 2.5, 2.7).
        val found = find(Regex("""\bdashPathEffect\b|\bPathEffect\b|\bDot(Panel|Header|Divider)\b|\bGlowDotBars\b"""))
        if (found.isNotEmpty()) fail("Lines, grids and panels are solid; only numbers are dots (spec 2.7):\n" + found.joinToString("\n"))
    }

    @Test fun `a list row that opens a metric shows its icon`() {
        // Spec 2.5, Grouped list: the metric's icon in place of the old dot. A row that opens something passes one.
        val found = mutableListOf<String>()
        sources.forEach { (path, lines) ->
            val code = lines.joinToString("\n")
            Regex("""\bListRow\(""").findAll(code).forEach { m ->
                val args = balanced(code, m.range.last)
                val line = code.substring(0, m.range.first).count { it == '\n' } + 1
                if (Regex("""\bonClick\s*=""").containsMatchIn(args) && !Regex("""\bicon\s*=""").containsMatchIn(args)) found += "$path:$line  ListRow(${args.trim().take(80)})"
            }
        }
        if (found.isNotEmpty()) fail("A row that opens something shows its metric's icon (spec 2.5):\n" + found.joinToString("\n"))
    }

    @Test fun `a card title stays on one line`() {
        // Spec 2.3: a note drops under the title ([TitleRow]) rather than wrap it. Every title text is one line.
        val found = mutableListOf<String>()
        sources.forEach { (path, lines) ->
            if (path in titleNotMigrated) return@forEach
            val code = lines.joinToString("\n")
            Regex("""\bText\(""").findAll(code).forEach { m ->
                val args = balanced(code, m.range.last)
                val line = code.substring(0, m.range.first).count { it == '\n' } + 1
                if ("GlowType.cardTitle" in args && !Regex("""\bmaxLines\s*=\s*1\b""").containsMatchIn(args)) found += "$path:$line  Text(${args.trim().take(80)})"
            }
        }
        if (found.isNotEmpty()) fail("A card title is one line (spec 2.3):\n" + found.joinToString("\n"))
        // Bars stay thin on a wide card, so a short one is never a dot (spec 2.7).
        assertTrue(BarMaxWidth <= 6.dp)
    }

    @Test fun `a card on Today and the tabs has one dotted number`() {
        // Spec 2.3 and audit 2.8: one number in dots a card; the rest is text. Each card is a function of its own. A
        // call that passes no number (EdgeValue(null, ...)) draws none.
        val found = mutableListOf<String>()
        val dotted = Regex("""\b(HeroNumber|HeroFigure|DotMatrixText|EdgeValue|FigureRow|GlowHeroNumber)\((?!\s*null\b)""")
        fun cards(vararg names: String) = Regex("""fun (${names.joinToString("|")})\(""")
        listOf(
            "glow/GlowTodayScreen.kt" to Regex("""private fun (\w+Hero)\("""),
            "glow/TodayCards.kt" to cards("WeekCard"),
            "glow/GlowRecoveryScreen.kt" to cards("RecoveryHero", "RecoveryFailedHero", "ChargeTall", "ChargeSmall"),
            "glow/GlowSleepScreen.kt" to cards("NightHero", "RegularityCard", "BreathingCard"),
            "glow/GlowActivityScreen.kt" to cards("StartCard", "StepsGlow", "LoadCard"),
            "glow/GlowHealthspan.kt" to cards("HabitAgeEdgeCard"),
        ).forEach { (path, fn) ->
            val code = sources.getValue(path).joinToString("\n")
            val heads = fn.findAll(code).toList()
            assertTrue("no cards found in $path", heads.isNotEmpty())
            heads.forEach { m ->
                val body = braced(code, code.indexOf('{', m.range.last + balanced(code, m.range.last).length + 1))
                val n = dotted.findAll(body).count()
                if (n > 1) found += "$path  ${m.groupValues[1]}: $n dotted numbers"
            }
        }
        if (found.isNotEmpty()) fail("One dotted number a card (spec 2.3):\n" + found.joinToString("\n"))
    }

    @Test fun `the sleep hero has no arc and Habit Age no How it works`() {
        // Spec 5.2: the score reads as a number and a word, no arc. The horizon arc is the Sleep score detail's only. Spec 2.5 and 5.4: an explanation is behind
        // (i) in the card, never a How it works panel on the screen.
        val found = find(Regex("""\b(GlowHorizon|HorizonArc|ArcGauge)\("""), except = sources.keys - "glow/GlowSleepScreen.kt") +
            find(Regex("""\bHowItWorks\w*\("""), except = sources.keys - setOf("glow/GlowHealthspanScreen.kt", "glow/GlowHealthspan.kt"))
        if (found.isNotEmpty()) fail("No arc on the sleep hero, no How it works on Habit Age:\n" + found.joinToString("\n"))
        assertTrue(sources.getValue("glow/GlowHealthspan.kt").any { "InfoButton(" in it })
    }

    @Test fun `Steps on Activity is a level glow like Charge`() {
        // Spec 2.1 Levels and 5.3: the light rises to the share of the goal over a black core, the number at the foot
        // in the second glow's dots, empty hours left out over the baseline.
        val code = sources.getValue("glow/GlowActivityScreen.kt").joinToString("\n")
        val steps = code.substringAfter("private fun StepsGlow(").substringBefore("\n}\n")
        assertTrue("StepsGlow uses the level recipe", "GlowStyle.level(MetricColors.steps" in steps)
        assertTrue("StepsGlow's number is 3.6 dp", "DotSize.Second" in steps)
        assertTrue("the bars come before the number", steps.indexOf("HourPills(") in 0 until steps.indexOf("HeroFigure("))
    }

    @Test fun `every hypnogram is drawn in the stage colours`() {
        // Spec 2.7: Last night on Today, the sleep hero and the Sleep detail all draw bands in the stage colours.
        val stages = sources.getValue("glow/GlowSleepScreen.kt").joinToString("\n").substringAfter("fun GlowSleepStages(").substringBefore("\n}\n")
        assertTrue("GlowSleepStages always passes the stage colours", "rowColors = colors" in stages && "colored" !in stages)
        val calls = find(Regex("""\bGlowHypnogram\(""")).filter { "fun GlowHypnogram" !in it }
        assertEquals("GlowHypnogram is drawn only through GlowSleepStages: $calls", 1, calls.size)
    }

    @Test fun `the Habit Age page lists its pace and what moves it`() {
        // Spec 5.4: grouped lists with icon rows, no plain panels, nothing to open so no carets.
        val page = sources.getValue("glow/GlowHealthspanScreen.kt").joinToString("\n")
        assertEquals(2, Regex("""\bListPanel\(""").findAll(page).count())
        assertTrue("no QuietPanel on the Habit Age page", "QuietPanel(" !in page && "MaterialTheme" !in page)
        assertTrue("every row has its icon and opens nothing", Regex("""\bListRow\(""").findAll(page).count() == Regex("""icon = """).findAll(page).count() && !Regex("""onClick\s*=(?!\s*null)""").containsMatchIn(page))
    }

    @Test fun `no metric has a second colour`() {
        val found = find(Regex("""\b(chargeScale|chargeAt|chargeDeep|readinessDeep)\b|MetricColors\.bad\b|\bval bad\b"""))
        if (found.isNotEmpty()) fail("Energy is one Ember, Recovery one teal, and bad is gone (spec 2.2):\n" + found.joinToString("\n"))
    }

    @Test fun `every dot-matrix number takes one of the six dot sizes`() {
        assertTrue("found no source under ${uiRoot.absolutePath}", sources.size > 50)
        val found = mutableListOf<String>()
        sources.forEach { (path, lines) ->
            val code = lines.joinToString("\n")
            Regex("""\bDotMatrixText\(""").findAll(code).forEach { m ->
                val args = balanced(code, m.range.last)
                val line = code.substring(0, m.range.first).count { it == '\n' } + 1
                // The declaration takes a DotSize; calls pass one, never a size or gap of their own.
                if (Regex("""\bdot\s*=\s*[^,]*\d\.dp|\bgap\s*=""").containsMatchIn(args)) found += "$path:$line  DotMatrixText(${args.trim()})"
            }
        }
        if (found.isNotEmpty()) fail("Dot sizes come from DotSize (spec 2.3):\n" + found.joinToString("\n"))
    }

    /** Card-sized corners: anything this round is a card, a panel or a sheet, and takes [GlowLayout.CardShape]. */
    @Test fun `cards take their corners from GlowLayout`() {
        val found = mutableListOf<String>()
        sources.forEach { (path, lines) ->
            if (path == "glow/GlowLayout.kt") return@forEach
            val code = lines.joinToString("\n")
            Regex("""\b(RoundedCornerShape|CornerRadius)\(""").findAll(code).forEach { m ->
                val args = balanced(code, m.range.last)
                val round = Regex("""(\d+(?:\.\d+)?)\.dp""").findAll(args).any { it.groupValues[1].toFloat() >= CARD_SIZED }
                val line = code.substring(0, m.range.first).count { it == '\n' } + 1
                if (round && (path to line) !in cornerAllowed) found += "$path:$line  ${m.value}${args.trim()})"
            }
        }
        // A style's own corner (GlowStyle(radius = 36.dp)); a circle's radius in a draw call is drawn in px.
        found += find(Regex("""\bradius\s*=\s*\d+(\.\d+)?\.dp\b(?!\.toPx)"""))
        if (found.isNotEmpty()) fail("Card corners are GlowLayout.CardCorner (spec 2.4):\n" + found.joinToString("\n"))
        val stale = cornerAllowed.filter { (path, line) ->
            sources[path]?.getOrNull(line - 1)?.let { Regex("""(RoundedCornerShape|CornerRadius)\(""").containsMatchIn(it) } != true
        }
        assertTrue("corner allowlist entries that no longer match: $stale", stale.isEmpty())
    }

    @Test fun `type sizes come from the type roles`() {
        val counts = sources.mapValues { (_, lines) -> lines.count { Regex("""\bfontSize\s*=""").containsMatchIn(it) } }
            .filterValues { it > 0 }
        val over = counts.filter { (path, n) -> path !in typeHomes && n > (typeNotMigrated[path] ?: 0) }
        if (over.isNotEmpty()) {
            fail(
                "Text takes its size from a GlowType role (spec 2.3), not a fontSize of its own:\n" +
                    over.keys.flatMap { p -> find(Regex("""\bfontSize\s*=""")).filter { it.startsWith("$p:") } }.joinToString("\n"),
            )
        }
        // The allowlist only shrinks: a migrated file comes off it.
        val stale = typeNotMigrated.filter { (path, n) -> (counts[path] ?: 0) < n }
        assertTrue("type allowlist is higher than the code, lower it: $stale (now ${stale.keys.associateWith { counts[it] ?: 0 }})", stale.isEmpty())
    }

    private companion object {
        /** From this radius up a corner is a card's. Tiles, chips, bars and pills stay under it. */
        const val CARD_SIZED = 20f

        /** Card-sized corners on things that aren't cards, by file and line, each with why. Empty since step 10. */
        val cornerAllowed = emptySet<Pair<String, Int>>()

        /** Files whose card titles may still wrap, until their step makes them one line. Empty since step 8. */
        val titleNotMigrated = emptySet<String>()

        /** Where type sizes are defined. */
        val typeHomes = setOf("glow/GlowType.kt", "theme/Theme.kt")

        /**
         * Files still setting their own type sizes, with how many, until their step moves them to the GlowType roles
         * (audit section 4). The list shrinks with each step and never grows.
         */
        val typeNotMigrated = mapOf(
            "components/Charts.kt" to 2,
            "dashboard/SleepPanels.kt" to 1,
            "glow/AllMetricsScreen.kt" to 1,
            "glow/GlowActivityScreen.kt" to 2,
            "glow/GlowCharts.kt" to 5,
            "glow/live/LiveFrame.kt" to 2,
            "glow/TabParts.kt" to 1,
        )
    }
}

/** The text between the `{` at [open] and its matching `}`. */
private fun braced(code: String, open: Int): String {
    var depth = 0
    for (i in open until code.length) {
        when (code[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return code.substring(open + 1, i)
        }
    }
    return code.substring(open + 1)
}

/** The text between the `(` at [open] and its matching `)`. */
private fun balanced(code: String, open: Int): String {
    var depth = 0
    for (i in open until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return code.substring(open + 1, i)
        }
    }
    return code.substring(open + 1)
}

/**
 * [src] with comments and the contents of string and char literals blanked, line breaks kept, so a scan sees only
 * code and line numbers still match. Template code inside strings is blanked too.
 */
private fun codeOnly(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
    while (i < src.length) {
        val c = src[i]
        when {
            src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') { blank(src[i]); i++ }
            src.startsWith("/*", i) -> {
                var depth = 0
                do {
                    when {
                        src.startsWith("/*", i) -> { depth++; blank(src[i]); blank(src[i + 1]); i += 2 }
                        src.startsWith("*/", i) -> { depth--; blank(src[i]); blank(src[i + 1]); i += 2 }
                        else -> { blank(src[i]); i++ }
                    }
                } while (depth > 0 && i < src.length)
            }
            src.startsWith("\"\"\"", i) -> {
                out.append("\"\"\""); i += 3
                while (i < src.length && !src.startsWith("\"\"\"", i)) { blank(src[i]); i++ }
                out.append("\"\"\""); i += 3
            }
            c == '"' || c == '\'' -> {
                out.append(c); i++
                while (i < src.length && src[i] != c && src[i] != '\n') {
                    if (src[i] == '\\') { blank(src[i]); i++ }
                    if (i < src.length) { blank(src[i]); i++ }
                }
                if (i < src.length) { out.append(src[i]); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}
