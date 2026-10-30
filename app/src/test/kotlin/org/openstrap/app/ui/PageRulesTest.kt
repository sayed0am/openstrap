package org.openstrap.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.ui.alarms.AlarmsUiState
import org.openstrap.app.ui.alarms.ROW_VALUE_MAX
import org.openstrap.app.ui.alarms.healthAlertsSummary
import org.openstrap.app.ui.device.StrapGlow
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import java.io.File

/**
 * The pages over a tab (spec 3.4: You, Strap and what they open, Edit Today and Goals) are built from the page parts,
 * not Material's: no outlined fields, text buttons, dialogs, checkboxes or Material type. Scans their code, comments
 * and strings left out, and fails with file and line.
 */
class PageRulesTest {
    private val root: File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val uiRoot = File(root, "kotlin/org/openstrap/app/ui")

    /** The page files, by their path from ui/. */
    private val pages = listOf(
        "alarms/AlarmsScreen.kt", "alarms/PhoneAlertsScreen.kt", "device/DeviceScreen.kt", "device/GoalsSheet.kt",
        "device/ProfileScreen.kt", "device/YouPage.kt", "firmware/FirmwareScreen.kt", "glow/EditTodaySheet.kt",
        "glow/PageParts.kt", "zepp/ZeppSignIn.kt",
    )

    private fun scan(pattern: Regex): List<String> = pages.flatMap { path ->
        val file = File(uiRoot, path)
        assertTrue("missing $path", file.isFile)
        codeOnly(file.readText()).lines().mapIndexedNotNull { i, line ->
            if (pattern.containsMatchIn(line)) "$path:${i + 1}  ${line.trim()}" else null
        }
    }

    @Test fun `pages use no Material parts`() {
        // TimePicker stays: the alarm sheet recolours Material's clock rather than drawing one of its own.
        val material = Regex(
            """\b(OutlinedTextField|TextField|TextButton|OutlinedButton|Button|IconButton|AlertDialog|GlassAlertDialog|Checkbox|Switch|""" +
                """FilterChip|LinearProgressIndicator|CircularProgressIndicator|Surface|Panel|PanelHeader)\(""",
        )
        val found = scan(material)
        if (found.isNotEmpty()) fail("Pages are built from PageButton, PageField, PageSheet and the rest (spec 3.4):\n" + found.joinToString("\n"))
    }

    @Test fun `pages take their type from GlowType`() {
        val found = scan(Regex("""MaterialTheme\.(typography|colorScheme)|\bfontSize\s*=|GlowType\.(label|title|headline)\b"""))
        if (found.isNotEmpty()) fail("Page text uses a GlowType role and the page's colours (spec 2.3):\n" + found.joinToString("\n"))
    }

    @Test fun `a Strap page value stays on one line`() {
        // Every combination of the three health alerts, each off or on.
        val keys = listOf(SettingsCodec.KEY_HIGH_HR_ALERT to 150, SettingsCodec.KEY_LOW_HR_ALERT to 40, SettingsCodec.KEY_LOW_SPO2_ALERT to 90)
        (0 until 8).forEach { mask ->
            val values = keys.mapIndexed { i, (k, v) -> k to if (mask and (1 shl i) != 0) v else 0 }
            val group = SettingsGroup(0, 1, values.associate { (k, v) -> k to SettingsCodec.enumSetting(k, v) })
            val text = healthAlertsSummary(AlarmsUiState(health = group))!!
            assertTrue("\"$text\" is longer than $ROW_VALUE_MAX", text.length <= ROW_VALUE_MAX)
        }
    }

    @Test fun `Profile asks for sex in labelled segments, not bare symbols`() {
        // Audit section 5: two unlabelled round icons beside Age read as a bulb and a magnifier. Words, like Units.
        val profile = File(uiRoot, "device/ProfileScreen.kt").readText()
        assertTrue("no round sex buttons", "SexToggle" !in profile && "GenderFemale" !in profile && "GenderMale" !in profile)
        assertTrue("Female and Male as segments", "GlowSegments(listOf(tr(R.string.profile_screen_female), tr(R.string.profile_screen_male))" in profile)
    }

    @Test fun `the strap hero is lit at full`() {
        // The Strap page's hero is L4 (spec 2.1): its rim at full strength.
        assertEquals(1f, StrapGlow.glowStrength, 1e-6f)
    }

    /** [src] with comments and string contents blanked, line breaks kept. */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') { blank(src[i]); i++ }
                src.startsWith("/*", i) -> {
                    while (i < src.length && !src.startsWith("*/", i)) { blank(src[i]); i++ }
                    repeat(2) { if (i < src.length) { blank(src[i]); i++ } }
                }
                src.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\""); i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) { blank(src[i]); i++ }
                    out.append("\"\"\""); i += 3
                }
                src[i] == '"' || src[i] == '\'' -> {
                    val q = src[i]
                    out.append(q); i++
                    while (i < src.length && src[i] != q && src[i] != '\n') {
                        if (src[i] == '\\') { blank(src[i]); i++ }
                        if (i < src.length) { blank(src[i]); i++ }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }
}
