package org.openstrap.app.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openstrap.protocol.services.Setting
import org.openstrap.protocol.services.SettingsCodec
import org.openstrap.protocol.services.SettingsGroup
import java.util.Locale

class FirmwareCloudTest {
    private val strap = StrapIdentity(firmwareVersion = "V3.1.5", hardwareVersion = "V0.1.0", serial = "HELIO1")

    private fun query(s: StrapIdentity = strap) = FirmwareCloud.queryParams(s, "1234", Locale.UK, "Europe/London", 34)

    /** The server keys new firmware on the Zepp version, so the check names the current one, as the account calls do. */
    @Test
    fun namesTheCurrentZeppApp() {
        val q = query()
        assertEquals("151997_10.10.5-play", q["cv"])
        assertEquals("10.10.5-play_151997", q["appVersion"])
        assertEquals(ZeppApp.ID, q["appid"])
    }

    @Test
    fun sendsZeppDefaultsWhenStrapReportsNoResources() {
        val q = query()
        // Zepp's values when group 0x00 is unread (codemap 07 §1.4).
        val expected = mapOf(
            "firmwareFlag" to "-1", "fontVersion" to "0", "fontFlag" to "0",
            "resourceVersion" to "-1", "resourceFlag" to "-1",
            "baseResourceVersion" to "-1", "baseResourceFlag" to "-1",
            "extResourceVersion" to "-1", "extResourceFlag" to "-1",
            "productionSource" to "-1",
            "vendorId" to "-1", "vendorSource" to "-1",
        )
        expected.forEach { (k, v) -> assertEquals(k, v, q[k]) }
        assertFalse("gpsVersion" in q)
    }

    @Test
    fun sendsKnownStrapValues() {
        val q = query(
            strap.copy(
                resources = StrapResources(firmwareFlag = 15, resourceVersion = 7, resourceFlag = 65, extResourceVersion = 2, extResourceFlag = 3),
                productionSource = 4,
            ),
        )
        assertEquals("15", q["firmwareFlag"])
        assertEquals("7", q["resourceVersion"])
        assertEquals("65", q["resourceFlag"])
        assertEquals("2", q["extResourceVersion"])
        assertEquals("3", q["extResourceFlag"])
        assertEquals("4", q["productionSource"])
    }

    private fun text(key: Int, value: String) =
        Setting(key, SettingsCodec.TYPE_STRING, (value + "\u0000").toByteArray(), null)

    @Test
    fun resourcesFromDeviceInformation() {
        val info = SettingsGroup(
            SettingsCodec.GROUP_DEVICE_INFORMATION, 1,
            listOf(
                Setting(SettingsCodec.KEY_LANGUAGE_FAMILY, SettingsCodec.TYPE_I32_B, byteArrayOf(15, 0, 0, 0), 15),
                text(SettingsCodec.KEY_BASE_RESOURCE_VERSION, "3,1"),
                text(SettingsCodec.KEY_RESOURCE_VERSION, "12,65"),
                text(SettingsCodec.KEY_EXT_RESOURCE_VERSION, "bad"),
            ).associateBy { it.key },
        )
        val r = StrapResources.from(info)
        assertEquals(15, r.firmwareFlag)
        assertEquals(12, r.resourceVersion); assertEquals(65, r.resourceFlag)
        // Zepp sends its base-resource fields unset even when the strap reports a pair (codemap 07 §1.4).
        assertEquals(-1, r.baseResourceVersion); assertEquals(-1, r.baseResourceFlag)
        // Missing font keeps Zepp's 0/0; an unparsable pair keeps -1/-1.
        assertEquals(0, r.fontVersion); assertEquals(0, r.fontFlag)
        assertEquals(-1, r.extResourceVersion); assertEquals(-1, r.extResourceFlag)
        val q = query(strap.copy(resources = r))
        assertEquals("15", q["firmwareFlag"]); assertEquals("12", q["resourceVersion"]); assertEquals("-1", q["baseResourceVersion"])
    }

    @Test
    fun noDeviceInformationKeepsDefaults() {
        assertEquals(StrapResources(), StrapResources.from(null))
        // Another group isn't mistaken for it.
        assertEquals(StrapResources(), StrapResources.from(SettingsGroup(SettingsCodec.GROUP_HEALTH, 3, emptyMap())))
    }

    @Test
    fun keepsIdentityParams() {
        val q = query()
        assertEquals("3.1.5", q["firmwareVersion"])
        assertEquals("0.1.0", q["hardwareVersion"])
        assertEquals("HELIO1", q["sn"])
        assertEquals("1234", q["userid"])
        assertEquals("1234", q["userId"])
        assertEquals("en_GB", q["lang"])
        assertEquals("android_34", q["device"])
        assertEquals("10289411", q["deviceSource"])
        assertEquals("157", q["productId"])
        assertEquals("true", q["support8Bytes"])
    }

    @Test
    fun omitsBlankSerialAndHardware() {
        val q = query(strap.copy(serial = " ", hardwareVersion = null))
        assertFalse("sn" in q)
        assertFalse("hardwareVersion" in q)
    }
}
