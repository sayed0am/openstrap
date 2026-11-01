package org.openstrap.app.testing

import org.junit.rules.ExternalResource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * A clock that stays where it is put. Production code reads time as epoch seconds passed in (`now: Long`) or from
 * `Instant.now()`, so this hands out both: [now] for the first and [clock] for `Instant.now(clock)` and the like.
 */
class FixedClock(var instant: Instant = Instant.parse("2026-01-15T12:00:00Z"), val zone: ZoneId = ZoneOffset.UTC) {
    /** Epoch seconds. */
    val now: Long get() = instant.epochSecond
    val today: LocalDate get() = instant.atZone(zone).toLocalDate()
    val clock: Clock get() = Clock.fixed(instant, zone)

    fun advance(by: Duration) {
        instant += by
    }
}

/** Runs each test in [locale], then puts the JVM's default locale back, so copy tests pass on any machine. */
class LocaleRule(private val locale: Locale = Locale.US) : ExternalResource() {
    private lateinit var saved: Triple<Locale, Locale, Locale>

    override fun before() {
        saved = Triple(Locale.getDefault(), Locale.getDefault(Locale.Category.FORMAT), Locale.getDefault(Locale.Category.DISPLAY))
        Locale.setDefault(locale)
    }

    override fun after() {
        Locale.setDefault(saved.first)
        Locale.setDefault(Locale.Category.FORMAT, saved.second)
        Locale.setDefault(Locale.Category.DISPLAY, saved.third)
    }
}

/** Runs each test in [zone], then puts the JVM's default time zone back. */
class ZoneRule(private val zone: ZoneId = ZoneOffset.UTC) : ExternalResource() {
    private lateinit var saved: TimeZone

    override fun before() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    override fun after() = TimeZone.setDefault(saved)
}
