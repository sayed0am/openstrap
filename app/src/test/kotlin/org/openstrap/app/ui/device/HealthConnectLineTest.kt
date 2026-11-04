package org.openstrap.app.ui.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.health.HcAvailability
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The You page's auto sync row: on and when it last wrote, off, or stopped and why. */
class HealthConnectLineTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 10, 29, 18, 5, 0, 0, zone).toEpochSecond()
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toEpochSecond()
    private fun line(auto: HealthConnectAuto) = healthConnectLine(auto, now, zone, Locale.UK)

    @Test fun `off by default`() {
        assertEquals("Off. Copies new data after each strap sync", line(HealthConnectAuto()))
    }

    @Test fun `on before the first write`() {
        assertEquals("On. Copies new data after each strap sync", line(HealthConnectAuto(on = true)))
    }

    @Test fun `on says when it last synced`() {
        assertEquals("On. Last synced 10:42", line(HealthConnectAuto(on = true, lastSynced = at(29, 10, 42))))
        assertEquals("On. Last synced yesterday 22:10", line(HealthConnectAuto(on = true, lastSynced = at(28, 22, 10))))
        assertEquals("On. Last synced Tuesday 09:00", line(HealthConnectAuto(on = true, lastSynced = at(27, 9, 0))))
        assertEquals("On. Last synced Mon 12 Oct 09:00", line(HealthConnectAuto(on = true, lastSynced = at(12, 9, 0))))
    }

    @Test fun `stopped says why`() {
        assertEquals(
            "Stopped. Health Connect permissions were turned off",
            line(HealthConnectAuto(lastSynced = at(29, 10, 42), stopped = HealthConnectAuto.Stopped.PERMISSIONS)),
        )
        assertEquals("Stopped. Health Connect isn't available on this phone", line(HealthConnectAuto(stopped = HealthConnectAuto.Stopped.UNAVAILABLE)))
    }

    @Test fun `the You page's row says it in a few words`() {
        fun summary(auto: HealthConnectAuto) = healthConnectSummary(auto, now, zone, Locale.UK)
        assertEquals("Off", summary(HealthConnectAuto()))
        assertEquals("On", summary(HealthConnectAuto(on = true)))
        assertEquals("On. Last synced 10:42", summary(HealthConnectAuto(on = true, lastSynced = at(29, 10, 42))))
        assertEquals("Stopped. Permissions were turned off", summary(HealthConnectAuto(stopped = HealthConnectAuto.Stopped.PERMISSIONS)))
        assertEquals("Stopped. Not available on this phone", summary(HealthConnectAuto(stopped = HealthConnectAuto.Stopped.UNAVAILABLE)))
    }

    @Test fun `stopped with Health Connect missing says to install it where the Play Store has it`() {
        val stopped = HealthConnectAuto(stopped = HealthConnectAuto.Stopped.UNAVAILABLE)
        assertEquals(
            "Stopped. Install Health Connect to turn it back on",
            healthConnectLine(stopped, now, zone, Locale.UK, HcAvailability.INSTALLABLE),
        )
        assertEquals("Stopped. Install Health Connect", healthConnectSummary(stopped, now, zone, Locale.UK, HcAvailability.INSTALLABLE))
        assertEquals(
            "Stopped. Health Connect isn't available on this phone",
            healthConnectLine(stopped, now, zone, Locale.UK, HcAvailability.UNAVAILABLE),
        )
        assertEquals("Stopped. Not available on this phone", healthConnectSummary(stopped, now, zone, Locale.UK, HcAvailability.UNAVAILABLE))
    }
}
