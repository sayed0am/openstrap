package org.openstrap.app.presentation

import java.text.DecimalFormatSymbols
import java.time.ZoneId
import java.util.Locale

/** The languages the presentation tests run under: a comma decimal mark, another script's digits, and the US. */
val TEST_LOCALES: List<Locale> = listOf(Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ar"), Locale.forLanguageTag("en-US"))

/** Parameters for a JUnit Parameterized run, one per [TEST_LOCALES]. */
fun localeParameters(): List<Array<Any>> = TEST_LOCALES.map { arrayOf(it) }

/** A zone with both clock changes: 2026-03-29 has 23 hours and 2026-10-25 has 25. */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * [ascii], written with a dot decimal mark, a comma thousands mark and ASCII digits, as [locale] writes numbers: its
 * digits and marks. What `String.format(locale, ...)` gives, worked out apart from it.
 */
fun localNumber(ascii: String, locale: Locale): String {
    val s = DecimalFormatSymbols.getInstance(locale)
    return ascii.map { c ->
        when {
            c in '0'..'9' -> s.zeroDigit + (c - '0')
            c == '.' -> s.decimalSeparator
            c == ',' -> s.groupingSeparator
            else -> c
        }
    }.joinToString("")
}
