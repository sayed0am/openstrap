package org.openstrap.app.export

import java.io.Reader

/**
 * Reads CSV records one at a time from [source], as [CsvExporter] writes them: commas separate cells, double quotes
 * wrap a cell, "" inside one is a quote, and a quoted cell may hold commas and line breaks. Records end at \n, \r\n
 * or \r outside quotes. Blank lines are skipped. It never closes [source], so it can read one zip entry and stop.
 */
class CsvReader(source: Reader) {
    private val input = source.buffered()
    private var pending = NONE

    private fun read(): Int = if (pending != NONE) pending.also { pending = NONE } else input.read()

    /** The next record's cells, or null at the end. */
    fun next(): List<String>? {
        while (true) {
            val cells = record() ?: return null
            if (cells.size > 1 || cells[0].isNotEmpty()) return cells
        }
    }

    /** Every record left. */
    fun all(): List<List<String>> = generateSequence { next() }.toList()

    /** Reads the next character if it is [code] and says so, else leaves it to be read next. */
    private fun peek(code: Int): Boolean {
        val next = read()
        if (next != code) pending = next
        return next == code
    }

    private fun record(): List<String>? {
        var c = read()
        if (c == -1) return null
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        while (c != -1) {
            val ch = c.toChar()
            when {
                quoted && ch == '"' -> if (peek('"'.code)) cell.append('"') else quoted = false
                quoted -> cell.append(ch)
                ch == '"' -> quoted = true
                ch == ',' -> { cells += cell.toString(); cell.clear() }
                ch == '\n' || ch == '\r' -> {
                    // A \r ends the record alone or with the \n after it.
                    if (ch == '\r') peek('\n'.code)
                    break
                }
                else -> cell.append(ch)
            }
            c = read()
        }
        cells += cell.toString()
        return cells
    }

    private companion object {
        /** [pending] holds no character read ahead. Not -1, which is the end of [input]. */
        const val NONE = -2
    }
}
