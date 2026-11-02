package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every row of widgets spec 3.1's duplicate table, and the moments around it. */
class MomentLeadTest {
    private val none = emptySet<WidgetKind>()
    private val all = WidgetKind.entries.toSet()

    @Test fun `waking leads with recovery, and with last night when a Recovery widget is placed`() {
        assertEquals(MomentLead.Recovery, momentLead(Moment.Waking, none))
        assertEquals(MomentLead.Recovery, momentLead(Moment.Waking, setOf(WidgetKind.Sleep, WidgetKind.Steps, WidgetKind.Start, WidgetKind.Moment)))
        assertEquals(MomentLead.LastNight, momentLead(Moment.Waking, setOf(WidgetKind.Recovery)))
    }

    @Test fun `waking with Recovery and Sleep both placed leads with energy`() {
        assertEquals(MomentLead.Energy, momentLead(Moment.Waking, setOf(WidgetKind.Recovery, WidgetKind.Sleep)))
    }

    @Test fun `midday leads with energy whatever is placed`() {
        assertEquals(MomentLead.Energy, momentLead(Moment.Midday, none))
        assertEquals(MomentLead.Energy, momentLead(Moment.Midday, all))
    }

    @Test fun `the workout window leads with Start, even with a Start widget placed`() {
        assertEquals(MomentLead.Start, momentLead(Moment.WorkoutWindow, none))
        assertEquals(MomentLead.Start, momentLead(Moment.WorkoutWindow, setOf(WidgetKind.Start)))
        assertEquals(MomentLead.Start, momentLead(Moment.WorkoutWindow, all))
    }

    @Test fun `evening and night lead with a sentence whatever is placed`() {
        for (m in listOf(Moment.Evening, Moment.Night)) {
            assertEquals(MomentLead.Sentence, momentLead(m, none))
            assertEquals(MomentLead.Sentence, momentLead(m, all))
        }
    }

    @Test fun `during and after a workout lead with the workout`() {
        assertEquals(MomentLead.Workout, momentLead(Moment.During, all))
        assertEquals(MomentLead.AfterWorkout, momentLead(Moment.After, all))
    }
}
