package org.openstrap.app.testing

/**
 * A string literal: its text without template code, the line it starts on, and whether no user ever sees it. [shape]
 * is the text with each template part as one [HOLE], [call] the innermost call it is an argument of ("" for none, `if`
 * and `when` count), and [before] the code just before it, for a named argument such as `label = "..."`.
 */
internal data class Literal(
    val text: String,
    val line: Int,
    val hidden: Boolean,
    val shape: String = text,
    val call: String = "",
    val before: String = "",
) {
    companion object {
        /** Where template code was in [shape]. */
        const val HOLE = '\u0001'
    }
}

/**
 * A small Kotlin lexer that finds string literals. It tracks the call each literal sits in (to skip log and exception
 * messages) and whether it's inside an `if (BuildConfig.DEBUG ...)` block or a `check(x) { ... }` message. Shared by
 * the source scans, CopyPunctuationTest and HardcodedEnglishTest.
 */
internal class KotlinLiterals(private val src: String) {
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
        val shape = StringBuilder()
        val hidden = hidden()
        val call = calls.lastOrNull().orEmpty()
        val before = code.takeLast(BEFORE).toString()
        while (i < src.length) {
            val c = peek()
            when {
                raw && c == '"' && peek(1) == '"' && peek(2) == '"' -> {
                    while (peek(3) == '"') advance().let { text.append(it); shape.append(it) }
                    i += 3
                    break
                }
                !raw && c == '"' -> { advance(); break }
                !raw && c == '\\' -> {
                    advance()
                    val ch = if (peek() == 'u') {
                        advance()
                        src.substring(i, i + 4).toInt(16).toChar().also { i += 4 }
                    } else advance()
                    text.append(ch)
                    shape.append(ch)
                }
                c == '$' && peek(1) == '{' -> {
                    i += 2
                    shape.append(Literal.HOLE)
                    code(endOnBrace = true)
                }
                c == '$' && (peek(1).isLetter() || peek(1) == '_') -> {
                    advance()
                    shape.append(Literal.HOLE)
                    while (peek().isLetterOrDigit() || peek() == '_') advance()
                }
                else -> advance().let { text.append(it); shape.append(it) }
            }
        }
        out += Literal(text.toString(), start, hidden, shape.toString(), call, before)
    }

    private companion object {
        /** How much code before a literal [Literal.before] keeps. */
        const val BEFORE = 40
    }
}
