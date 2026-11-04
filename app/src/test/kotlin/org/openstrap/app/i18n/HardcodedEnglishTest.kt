package org.openstrap.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.testing.KotlinLiterals
import org.openstrap.app.testing.Literal
import org.openstrap.app.testing.SourceTree

/**
 * No new English in Kotlin: user-facing text lives in res/values/strings_<area>.xml, so it can be translated
 * (docs/audits/2026-11-04-i18n-plan.md). Scans every string literal under app/src/main/kotlin with the lexer the
 * punctuation test uses, and fails on one that reads as English in a file that isn't on [ALLOWED]. It also fails on a
 * listed file that has none left, so each wave takes off the files it moved and the list only shrinks.
 *
 * What counts as English is [EnglishText]. Literals that read like it but are never shown go in [IGNORED], by file and
 * call, each with why.
 */
class HardcodedEnglishTest {
    @Test fun `user-facing English stays in the files that still have it`() {
        val files = SourceTree.kotlin.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        assertTrue("found only ${files.size} sources under ${SourceTree.kotlin}", files.size > 100)
        val found = mutableListOf<String>()
        val withEnglish = mutableSetOf<String>()
        val used = mutableSetOf<Pair<String, String>>()
        for (f in files) {
            val path = f.relativeTo(SourceTree.kotlin).invariantSeparatorsPath
            for (lit in KotlinLiterals(f.readText()).scan()) {
                if (lit.hidden || !EnglishText.isText(lit)) continue
                val key = path to lit.call.substringAfterLast('.')
                if (key in IGNORED) {
                    used += key
                    continue
                }
                withEnglish += path
                if (path !in ALLOWED) found += "$path:${lit.line}  \"${lit.text}\""
            }
        }
        println("HardcodedEnglishTest: ${withEnglish.size} files still have English, ${ALLOWED.size} allowed")
        if (found.isNotEmpty()) {
            fail(
                "${found.size} English strings in Kotlin. Move them to res/values/strings_<area>.xml " +
                    "(docs/audits/2026-11-04-i18n-plan.md, How to move a file):\n" + found.joinToString("\n"),
            )
        }
        val clean = ALLOWED - withEnglish
        assertTrue("these files have no English left, take them off ALLOWED: $clean", clean.isEmpty())
        val stale = IGNORED.keys - used
        assertTrue("IGNORED entries that no longer match: $stale", stale.isEmpty())
    }

    @Test fun `text with spaces, units and placeholders counts, keys, patterns, SQL and labels don't`() {
        val src = """
            Text("Check now")
            Text("Updates")
            Text("${'$'}steps steps")
            val a = "Goal ${'$'}{goal}"
            val b = "%d min"
            val c = "Synced "
            Icon(contentDescription = "Open You")
            val d = "${'$'}{x} bpm"
            val key = "start_epoch"
            val route = "workout/{id}"
            val camel = "workoutStart"
            val single = "bpm"
            val caps = "HRV"
            val pattern = "d MMM yyyy"
            val sql = "SELECT * FROM night WHERE day BETWEEN ? AND ?"
            val json = "{\"type\":\"Feature\"}"
            val url = "https://example.com/a b"
            val ident = "BoundDevice(deviceId=${'$'}id, mac=${'$'}mac)"
            @Suppress("LongParameterList")
            Modifier.testTag("save button")
            animateFloatAsState(1f, label = "nav pill")
            log("sync started")
            Log.w(TAG, "sync failed")
            throw IllegalStateException("No reply from the strap")
            // A comment with words in it
            /** KDoc with "quoted words" */
        """.trimIndent()
        val text = KotlinLiterals(src).scan().filter { !it.hidden && EnglishText.isText(it) }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), text.map { it.line })
    }

    @Test fun `the English check reads the template holes and the call each literal is in`() {
        val lit = KotlinLiterals("Text(label = \"Goal ${'$'}goal\")").scan().single()
        assertEquals("Goal ", lit.text)
        assertEquals("Goal ${Literal.HOLE}", lit.shape)
        assertEquals("Text", lit.call)
        assertTrue(lit.before.endsWith("label = "))
    }

    private companion object {
        /**
         * Literals the check reads as English that no user sees, by file and the call they are an argument of ("" for
         * none), each with why. Prefer a narrower entry to a broad one.
         */
        val IGNORED = mapOf(
            ("ble/BleScanner.kt" to "listOf") to "The names the official app accepts for the strap, matched in scans",
            ("ble/GattClient.kt" to "fail") to "Why a GATT call failed, for the diagnostics log. Callers show their own words",
            ("ble/GattClient.kt" to "failAny") to "Why the link failed, for the diagnostics log. Callers show their own words",
            ("data/DetailRoutes.kt" to "") to "Health Connect detail route ids, read back by the app, never shown",
            ("data/db/DayKeyMigration.kt" to "") to "The migration's notes for the diagnostics log",
            ("device/HealthAlertNotifier.kt" to "") to "Names of the metric screens an alert opens, never shown",
            ("device/LeftRecording.kt" to "End") to "Why a workout left recording was ended, for the diagnostics log",
            ("data/settings/SettingsStore.kt" to "PairedDevice") to "The strap's product name, a name in every language",
            ("device/StrapManager.kt" to "step") to "Connection steps named in the diagnostics log",
            ("device/StrapPresenceService.kt" to "appeared") to "Presence events named in the diagnostics log",
            ("device/WorkoutGapFill.kt" to "") to "Its result line for the diagnostics log",
            ("device/WorkoutSession.kt" to "buzz") to "Buzz reasons named in the diagnostics log",
            ("device/WorkoutSession.kt" to "End") to "Why a workout left recording was ended, for the diagnostics log",
            ("export/BackupFiles.kt" to "if") to "Matches the system's own No space error message",
            ("export/CsvBackups.kt" to "warn") to "Backup warnings for the diagnostics log",
            ("health/AutoHealthConnect.kt" to "guarded") to "Write names for the diagnostics log",
            ("ui/dashboard/Narrative.kt" to "") to "The sleep contributors' keys, matched by SleepCopy.factors and never shown",
            ("ui/dashboard/Routes.kt" to "") to "The screens an open_metric link names, matched and never shown",
            ("ui/dashboard/Routes.kt" to "Calories") to "The name a saved detail stack knows Calories by, never shown",
            ("ui/dashboard/Routes.kt" to "Energy") to "The name a saved detail stack knows Energy by, never shown",
            ("ui/dashboard/SyncStates.kt" to "Failed") to "The debug strap preview's sync failure reason, never shown",
            ("health/HealthConnectExporter.kt" to "Device") to "The strap's maker and model as Health Connect records them",
            ("ui/glow/draw/GlowStyle.kt" to "listOf") to "Names of the glow presets, kept for tuning and never shown",
            ("ui/device/DeviceViewModel.kt" to "") to "The strap's product name, saved as a new strap's name, the same in every language",
            ("ui/device/DeviceViewModel.kt" to "failed") to "Failed actions named in the diagnostics log",
            ("ui/zepp/ZeppSignIn.kt" to "mapOf") to "Sign-in providers' brand names, the same in every language",
            ("ui/device/PageRowKeys.kt" to "") to "The page host's keys for the You and Strap rows, matched and never shown",
            ("ui/firmware/FirmwareViewModel.kt" to "failed") to "Failed steps named in the diagnostics log",
            ("ui/zepp/ZeppSignIn.kt" to "diagnostics") to "The device list's failure for the diagnostics log",
            ("ui/root/AppRootViewModel.kt" to "write") to "Setting names for the log line when a write fails",
            ("ui/theme/AppIcons.kt" to "line") to "Icon names, the ImageVector's own name",
            ("update/ReleaseNotes.kt" to "startsWith") to "GitHub's generated release notes line it leaves out",
            ("widget/WidgetSnapshot.kt" to "logUnread") to "What a widget couldn't read, for the log",
            ("widget/WidgetSnapshot.kt" to "read") to "What a widget couldn't read, for the log",
        )

        /**
         * Every file that still has English in Kotlin, which wave 0 found. Each wave moves its files' text to
         * strings_<area>.xml and takes them off. Wave 6 empties it.
         */
        val ALLOWED = emptySet<String>()
    }
}

/**
 * Whether a literal reads as user-facing English: two words (`"Check now"`), one word beside a value
 * (`"$steps steps"`, `"Goal $goal"`, `"%d min"`), or one capitalised word (`"Updates"`). A word has at least two
 * letters, all lower case after the first, so keys (`start_epoch`, `workoutStart`), routes, abbreviations (`HRV`),
 * date patterns and identifiers don't count, and neither does SQL (an upper case keyword) or a URL.
 *
 * It deliberately leaves out a lone lower case word (`"bpm"`, `"days"`, `"km"`), which can't be told from a key by
 * its look, and words with digits (`"VO2 max"`). Both are found with the rest of their file's text when a wave moves
 * it. It also skips the arguments of calls that are never shown ([QUIET]) and Compose animation labels (`label =`).
 */
internal object EnglishText {
    /** Calls whose string arguments no user reads: annotations, SQL, patterns, keys, HTTP headers and the logs. */
    private val QUIET = setOf(
        "Suppress", "SuppressLint", "Preview", "Deprecated", "Query", "RawQuery", "ColumnInfo", "execSQL", "rawQuery",
        "testTag", "Regex", "toRegex", "ofPattern", "of", "log", "setRequestProperty", "launchForBroadcast",
    )

    private val label = Regex("""\blabel\s*=\s*$""")
    private val sql = Regex("""^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|PRAGMA|WITH)\b|\b(FROM|WHERE|BETWEEN)\b""")
    private val single = Regex("""[A-Z][a-z'’]+(?:-[a-z]+)*[.!?:…]?""")
    private val word = Regex("""[("'“‘]*[A-Za-z][a-z'’]*(?:-[A-Za-z]?[a-z'’]+)*[)"'”’.,!?:…%]*""")
    private val hole = Regex(
        """[(\u0001\d%.,:+\-−/×]*\u0001[\u0001\d%.,:+\-−/)]*|%[-#+ 0,(]*\d*\$?\d*(?:\.\d+)?[a-zA-Z][,.]?""",
    )

    fun isText(lit: Literal): Boolean {
        val call = lit.call.substringAfterLast('.')
        if (call in QUIET || call.endsWith("PreferencesKey") || label.containsMatchIn(lit.before)) return false
        val s = lit.shape.trim()
        if (s.isEmpty() || "://" in s || sql.containsMatchIn(s)) return false
        if (s.none(Char::isWhitespace)) return single.matches(s)
        val chunks = s.split(' ', '\t', '\n', '\u00A0', '\u2007', '\u2009', '\u202F').filter { it.isNotEmpty() }
        val words = chunks.count { word.matches(it) && it.count(Char::isLetter) >= 2 }
        return words >= 2 || (words == 1 && chunks.any { hole.matches(it) })
    }
}
