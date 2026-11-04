package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.openstrap.protocol.history.HistoryDataType
import org.openstrap.protocol.history.HistoryTypeSpecs

class SyncKindNameTest {
    @Test fun `a sync step names its data in words, not the protocol's`() {
        assertEquals("heart rate", syncKindName(HistoryDataType.SEC_HR_V2))
        assertEquals("blood oxygen", syncKindName(HistoryDataType.SPO2_OSA))
        assertEquals("heart points", syncKindName(HistoryDataType.PAI))
        assertEquals("workouts", syncKindName(HistoryDataType.SPORT_SUMMARY))
        assertEquals("other data", syncKindName(HistoryDataType.ECG))
    }

    @Test fun `every type a sync fetches has a name of its own`() {
        HistoryTypeSpecs.all.filter { it.parser != null }.forEach { spec ->
            assertNotEquals(spec.type.name, "other data", syncKindName(spec.type))
        }
    }
}
