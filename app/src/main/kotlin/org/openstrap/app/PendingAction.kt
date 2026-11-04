package org.openstrap.app

import androidx.compose.runtime.saveable.Saver
import androidx.lifecycle.ViewModel
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.Strength
import org.openstrap.app.data.WorkoutPlan
import org.openstrap.app.data.WorkoutTarget

/**
 * What to do once a permission screen or a system setting answers. It is kept as data, not a closure, so it
 * survives the activity being recreated behind that screen: a fold, a rotation, or the process dying while
 * Settings is open. The answer then comes back to a new composition, which runs the action again (audit E2).
 */
internal sealed interface PendingAction {
    /** Start [plan] once location (for GPS) and Bluetooth have been asked for. [saved] is what Ready keeps for the sport. */
    data class StartPlan(val plan: WorkoutPlan, val saved: SportSettings?) : PendingAction

    /**
     * Install the release tagged [tag] once "Install unknown apps" is on. [answered]: back from that setting, so the
     * install goes ahead as soon as the release is known again (after process death the list loads a moment later).
     */
    data class Install(val tag: String, val answered: Boolean = false) : PendingAction

    companion object {
        private const val START_PLAN = "plan"
        private const val INSTALL = "install"

        /**
         * For `rememberSaveable`: an action as a list of strings, ints, booleans and nulls, which a saved instance
         * state bundle can hold. Anything unreadable restores as nothing pending.
         */
        val Saver: Saver<PendingAction?, Any> = Saver(
            save = { action -> action?.let(::encode) },
            restore = { saved -> (saved as? List<*>)?.let(::decode) },
        )

        private fun encode(action: PendingAction): ArrayList<Any?> = when (action) {
            is StartPlan -> with(action.plan) {
                arrayListOf(
                    START_PLAN, sportType, askToEnd, target?.code, useGps, keepRoute, rest?.code, setsTarget, logSets, structure,
                    action.saved?.encode(),
                )
            }
            is Install -> arrayListOf(INSTALL, action.tag, action.answered)
        }

        private fun decode(list: List<*>): PendingAction? {
            // Read in the order [encode] wrote them, so the fields need no positions of their own.
            val fields = list.drop(1).iterator()
            fun next(): Any? = if (fields.hasNext()) fields.next() else null
            return when (list.firstOrNull()) {
                START_PLAN -> (next() as? Int)?.let { sport ->
                    StartPlan(
                        WorkoutPlan(
                            sportType = sport,
                            askToEnd = next() as? Boolean ?: true,
                            target = WorkoutTarget.parse(next() as? String),
                            useGps = next() as? Boolean ?: false,
                            keepRoute = next() as? Boolean ?: false,
                            rest = Strength.Rest.parse(next() as? String),
                            setsTarget = next() as? Int,
                            logSets = next() as? Boolean ?: false,
                            structure = next() as? String,
                        ),
                        (next() as? String)?.let(SportSettings::parse),
                    )
                }
                INSTALL -> (next() as? String)?.let { tag -> Install(tag, next() as? Boolean ?: false) }
                else -> null
            }
        }
    }
}

/**
 * A Strap page action waiting on the Bluetooth answer. The page hands over a closure (scan, connect, pair), which
 * can't be saved, but a view model outlives a fold or a rotation, and the closures only reach the device view model,
 * which lives as long. After process death it is gone, and the reconnect on resume covers a connect.
 */
internal class PendingStrapAction : ViewModel() {
    var action: (() -> Unit)? = null
}
