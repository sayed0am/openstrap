package org.openstrap.app

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.saveable.SaveableStateRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutPlan
import org.openstrap.app.data.WorkoutTarget
import org.robolectric.RobolectricTestRunner
import java.io.Serializable

/**
 * A pending permission action goes through what an activity recreate does to `rememberSaveable`: the registry saves
 * it, the values go into the saved instance state bundle, which is parcelled (as when the process dies behind
 * Settings), and a new registry restores it for the new composition (audit E2).
 */
@RunWith(RobolectricTestRunner::class)
class PendingActionTest {
    private fun recreated(action: PendingAction?): PendingAction? {
        val before = SaveableStateRegistry(null) { it is Serializable }
        val saver = PendingAction.Saver
        before.registerProvider(KEY) { with(saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(action) } }
        val bundle = Bundle().apply {
            before.performSave().forEach { (key, values) -> putSerializable(key, ArrayList(values)) }
        }
        val parcel = Parcel.obtain()
        val restoredBundle = try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
        @Suppress("DEPRECATION")
        val values = restoredBundle.keySet().associateWith { restoredBundle.getSerializable(it) as List<Any?> }
        val after = SaveableStateRegistry(values) { it is Serializable }
        return after.consumeRestored(KEY)?.let { saver.restore(it) }
    }

    @Test fun `a workout waiting on a permission starts the same after a recreate`() {
        val plan = WorkoutPlan(
            sportType = 1, askToEnd = false, target = WorkoutTarget.Distance(5000), useGps = true, keepRoute = true,
            rest = Strength.Rest(90, 110), setsTarget = 4, logSets = true, structure = "t:30/30x8:w",
        )
        val saved = SportSettings(goal = "d:5000", askToEnd = false, keepRoute = true, structure = "t:30/30x8:w")
        assertEquals(PendingAction.StartPlan(plan, saved), recreated(PendingAction.StartPlan(plan, saved)))
    }

    @Test fun `a plain plan with nothing set and nothing to save comes back as it was`() {
        val plan = WorkoutPlan(sportType = 24)
        assertEquals(PendingAction.StartPlan(plan, null), recreated(PendingAction.StartPlan(plan, null)))
    }

    @Test fun `an install waiting on Install unknown apps keeps its release and whether Settings answered`() {
        assertEquals(PendingAction.Install("v3.0.0"), recreated(PendingAction.Install("v3.0.0")))
        assertEquals(PendingAction.Install("v3.0.0", answered = true), recreated(PendingAction.Install("v3.0.0", answered = true)))
    }

    @Test fun `nothing pending stays nothing, and unreadable state restores as nothing`() {
        assertNull(recreated(null))
        assertNull(PendingAction.Saver.restore(listOf("plan")))
        assertNull(PendingAction.Saver.restore(listOf("something else", 1)))
        assertNull(PendingAction.Saver.restore("not a list"))
    }

    private companion object {
        const val KEY = "pending"
    }
}
