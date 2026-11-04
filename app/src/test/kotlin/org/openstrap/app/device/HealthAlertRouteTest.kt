package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.presentation.Metric
import org.openstrap.protocol.services.HealthAlert

/** Each health alert opens a metric screen that exists, though the notifier names it without the screens' types. */
class HealthAlertRouteTest {
    @Test
    fun everyAlertNamesAMetric() {
        for (type in HealthAlert.Type.entries) Metric.valueOf(HealthAlertNotifier.route(type))
        assertEquals(Metric.HeartRate.name, HealthAlertNotifier.route(HealthAlert.Type.IRREGULAR_RHYTHM))
        assertEquals(Metric.Stress.name, HealthAlertNotifier.route(HealthAlert.Type.HIGH_STRESS))
        assertEquals(Metric.Spo2.name, HealthAlertNotifier.route(HealthAlert.Type.LOW_SPO2))
    }
}
