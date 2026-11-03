package org.openstrap.app.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.testing.SourceTree
import java.io.File

/**
 * No interpunct, semicolon or em dash in any string the user reads (spec 7.1 rule 6, 8.2). Scans the string literals
 * of the Kotlin source that holds copy, leaving out code, comments, template code, log and exception messages and
 * debug-only branches. Ranges say "to" (user, 2026-11-03): the second test bans the en dash and the em dash in every
 * string of the app and the protocol module, exception messages included, since some reach the screen.
 */
class CopyPunctuationTest {
    private val banned = setOf('·', ';', '—')

    /** Literals that are never shown, by file and exact text, each with why. Prefer fixing the string. */
    private val allowed = mapOf(
        ("ui/zepp/ZeppSignIn.kt" to "; ") to "Joins cookies into the Cookie header sent to Zepp, never shown",
    )

    private val root: File = SourceTree.root
    private val kotlinRoot = SourceTree.kotlin

    /** Everything under ui/ and widget/, every *Copy*.kt, the notification text in StrapService and data the UI shows as is. */
    private fun sources(): List<File> {
        val all = kotlinRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val named = setOf("device/StrapService.kt", "data/Recovery.kt", "data/settings/Goals.kt")
        return all.filter { f ->
            val path = f.relativeTo(kotlinRoot).invariantSeparatorsPath
            path.startsWith("ui/") || path.startsWith("widget/") || "Copy" in f.name || path in named
        }.sortedBy { it.path }
    }

    @Test fun `no user-facing string uses an interpunct, semicolon or em dash`() {
        val files = sources()
        assertTrue("found no source under ${kotlinRoot.absolutePath}", files.size > 50)
        val found = mutableListOf<String>()
        val used = mutableSetOf<Pair<String, String>>()
        files.forEach { f ->
            val path = f.relativeTo(kotlinRoot).invariantSeparatorsPath
            KotlinLiterals(f.readText()).scan().forEach { lit ->
                if (lit.text.none { it in banned } || lit.hidden) return@forEach
                val key = path to lit.text
                if (key in allowed) used += key else found += "$path:${lit.line}  \"${lit.text}\""
            }
        }
        File(root, "res/values/strings.xml").takeIf { it.isFile }?.readLines()?.forEachIndexed { i, line ->
            if (line.any { it in banned }) found += "res/values/strings.xml:${i + 1}  ${line.trim()}"
        }
        // The widgets' provider XML (widgets spec 8).
        File(root, "res/xml").listFiles().orEmpty().filter { it.name.startsWith("widget_") && it.extension == "xml" }.sortedBy { it.name }.forEach { f ->
            f.readLines().forEachIndexed { i, line -> if (line.any { it in banned }) found += "res/xml/${f.name}:${i + 1}  ${line.trim()}" }
        }
        // The picker previews' sample copy (widgets spec 6.4).
        File(root, "res/layout").listFiles().orEmpty().filter { it.name.startsWith("widget_preview_") }.sortedBy { it.name }.forEach { f ->
            f.readLines().forEachIndexed { i, line -> if (line.any { it in banned }) found += "res/layout/${f.name}:${i + 1}  ${line.trim()}" }
        }
        if (found.isNotEmpty()) fail("${found.size} user-facing strings use ·, ; or —:\n" + found.joinToString("\n"))
        val stale = allowed.keys - used
        assertTrue("allowlist entries that no longer match: $stale", stale.isEmpty())
    }

    /**
     * Literals with an en or em dash that aren't text, by file (from `app/src/main/kotlin/org/openstrap/app`, or
     * `protocol:` and the path from `protocol/src/main/kotlin/org/openstrap/protocol`) and exact text, each with why.
     * Empty: every range says "to". Keep it for a dash that is data, never for words.
     */
    private val dashAllowed = emptyMap<Pair<String, String>, String>()

    private val dashes = setOf('–', '—')

    /** The protocol module's Kotlin, beside the app module. */
    private val protocolRoot = File(SourceTree.module.parentFile, "protocol/src/main/kotlin/org/openstrap/protocol")

    @Test fun `no string in the app or the protocol joins a range with a dash or uses an em dash`() {
        val found = mutableListOf<String>()
        val used = mutableSetOf<Pair<String, String>>()
        fun scan(dir: File, prefix: String): Int {
            val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
            files.forEach { f ->
                val path = prefix + f.relativeTo(dir).invariantSeparatorsPath
                KotlinLiterals(f.readText()).scan().forEach { lit ->
                    if (lit.text.none { it in dashes }) return@forEach
                    val key = path to lit.text
                    if (key in dashAllowed) used += key else found += "$path:${lit.line}  \"${lit.text}\""
                }
            }
            return files.size
        }
        assertTrue("found no source under ${kotlinRoot.absolutePath}", scan(kotlinRoot, "") > 50)
        assertTrue("found no source under ${protocolRoot.absolutePath}", scan(protocolRoot, "protocol:") > 10)
        // Every resource the user can read: strings, the widgets' provider XML and the picker previews.
        File(root, "res").walkTopDown().filter { it.isFile && it.extension == "xml" }.sortedBy { it.path }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.any { it in dashes }) found += "res/${f.relativeTo(File(root, "res")).invariantSeparatorsPath}:${i + 1}  ${line.trim()}"
            }
        }
        if (found.isNotEmpty()) fail("${found.size} strings use an en or em dash, write a range with \"to\":\n" + found.joinToString("\n"))
        val stale = dashAllowed.keys - used
        assertTrue("allowlist entries that no longer match: $stale", stale.isEmpty())
    }

    @Test fun `the dash scan reads exception and log messages too`() {
        val src = """
            throw IllegalStateException("No reply — rebooting")
            Log.w(TAG, "10–12")
            val a = "22:58 to 06:56"
        """.trimIndent()
        val withDash = KotlinLiterals(src).scan().filter { lit -> lit.text.any { it in dashes } }
        assertTrue(withDash.map { it.line }.toString(), withDash.map { it.line } == listOf(1, 2))
    }

    @Test fun `the scanner skips code, comments, template code, logs, exceptions and debug branches`() {
        val src = """
            // a comment; with — and ·
            /* block; /* nested · */ still — comment */
            /** KDoc; "quoted" */
            val a = 1; val b = 'x'; val q = '"'
            val c = "kept · here"
            val d = "${'$'}{a; b} fine"
            val e = ""${'"'}raw; text""${'"'}
            Log.w(TAG, "log; text")
            throw IllegalStateException("bad; state")
            if (BuildConfig.DEBUG) { show("debug; only") }
            check(x) { "nope; ${'$'}{y ?: "inner — dash"}" }
            val f = "${'$'}{if (on) "on — off" else ""}"
            val g = "—"
        """.trimIndent()
        val shown = KotlinLiterals(src).scan().filter { !it.hidden && it.text.any { c -> c in banned } }
        assertTrue(shown.map { it.text }.toString(), shown.map { it.line } == listOf(5, 7, 12, 13))
    }
}

/** A string literal: its text without template code, the line it starts on, and whether no user ever sees it. */
private data class Literal(val text: String, val line: Int, val hidden: Boolean)

/**
 * A small Kotlin lexer that finds string literals. It tracks the call each literal sits in (to skip log and exception
 * messages) and whether it's inside an `if (BuildConfig.DEBUG ...)` block or a `check(x) { ... }` message.
 */
private class KotlinLiterals(private val src: String) {
    private var i = 0
    private var line = 1
    private val out = mutableListOf<Literal>()
    private val code = StringBuilder()
    private val calls = ArrayDeque<String>()
    private val blocks = ArrayDeque<Boolean>()
    private var closed = ""

    fun scan(): List<Literal> {
        code(endOnBrace = false)
        return out
    }

    private fun peek(k: Int = 0) = src.getOrElse(i + k) { '\u0000' }

    private fun advance(): Char {
        val c = src[i++]
        if (c == '\n') line++
        return c
    }

    private fun hidden(): Boolean = blocks.any { it } || calls.any { quiet(it) }

    private fun quiet(call: String): Boolean {
        val name = call.substringAfterLast('.')
        return call.matches(Regex("""(.*\.)?Log\.[vdiwe]""")) || name.endsWith("Exception") || name.endsWith("Error") ||
            name in setOf("error", "require", "check", "requireNotNull", "checkNotNull", "TODO")
    }

    /** Code until the end, or until the `}` that closes a template expression. */
    private fun code(endOnBrace: Boolean) {
        var depth = 0
        while (i < src.length) {
            val c = peek()
            when {
                c == '/' && peek(1) == '/' -> while (i < src.length && peek() != '\n') advance()
                c == '/' && peek(1) == '*' -> blockComment()
                c == '"' && peek(1) == '"' && peek(2) == '"' -> { i += 3; string(raw = true); code.append("\"\"") }
                c == '"' -> { advance(); string(raw = false); code.append("\"\"") }
                c == '\'' -> charLiteral()
                c == '(' -> {
                    calls.addLast(code.trimEnd().takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '.' }.toString())
                    code.append(advance())
                }
                c == ')' -> { closed = calls.removeLastOrNull() ?: ""; code.append(advance()) }
                c == '{' -> {
                    // A debug branch, or the message lambda of check(x) { "..." } and the like.
                    val tail = code.takeLast(200).toString().substringAfterLast('{').substringAfterLast('}')
                    blocks.addLast("BuildConfig.DEBUG" in tail || (code.trimEnd().endsWith(')') && quiet(closed)))
                    depth++
                    code.append(advance())
                }
                c == '}' -> {
                    if (endOnBrace && depth == 0) { advance(); return }
                    blocks.removeLastOrNull()
                    depth--
                    code.append(advance())
                }
                else -> code.append(advance())
            }
        }
    }

    private fun blockComment() {
        var depth = 0
        do {
            when {
                peek() == '/' && peek(1) == '*' -> { depth++; i += 2 }
                peek() == '*' && peek(1) == '/' -> { depth--; i += 2 }
                else -> advance()
            }
        } while (depth > 0 && i < src.length)
    }

    private fun charLiteral() {
        advance()
        if (peek() == '\\') advance()
        advance()
        while (i < src.length && peek() != '\'') advance()
        advance()
        code.append("' '")
    }

    /** One string literal, after its opening quote. Template code goes back through [code]. */
    private fun string(raw: Boolean) {
        val start = line
        val text = StringBuilder()
        val hidden = hidden()
        while (i < src.length) {
            val c = peek()
            when {
                raw && c == '"' && peek(1) == '"' && peek(2) == '"' -> {
                    while (peek(3) == '"') text.append(advance())
                    i += 3
                    break
                }
                !raw && c == '"' -> { advance(); break }
                !raw && c == '\\' -> {
                    advance()
                    if (peek() == 'u') {
                        advance()
                        text.append(src.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    } else text.append(advance())
                }
                c == '$' && peek(1) == '{' -> { i += 2; code(endOnBrace = true) }
                c == '$' && (peek(1).isLetter() || peek(1) == '_') -> {
                    advance()
                    while (peek().isLetterOrDigit() || peek() == '_') advance()
                }
                else -> text.append(advance())
            }
        }
        out += Literal(text.toString(), start, hidden)
    }
}
