package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.dashboard.TodayCard
import org.openstrap.app.ui.dashboard.TodayEdits

/**
 * Edit Today (audit 3, Sheets): which cards Today shows, and up to two pinned under the hero, as a sheet of rows.
 * Each row has the card's name, a Pin chip when it can be pinned, and a switch for whether it shows. Saved as a whole
 * on Done. Pulling the sheet down or Cancel leaves Today as it was. The pins and hides not yet saved outlive a rotation,
 * a fold and process death ([TodayEditsSaver]).
 */
@Composable
internal fun EditTodaySheet(edits: TodayEdits, onSave: (TodayEdits) -> Unit, onDismiss: () -> Unit) {
    var e by rememberSaveable(stateSaver = TodayEditsSaver) { mutableStateOf(edits) }
    PageSheet(onDismiss, "Edit Today", note = "Pin up to two cards under the top card, or hide the ones you don't need.") {
        PageRows {
            TodayCard.entries.filter { it.editable }.forEachIndexed { i, card ->
                val shown = card !in e.hidden
                PageRow(card.label, divider = i > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (card.pinnable) {
                            val pinned = card in e.pinned
                            PageChip(if (pinned) "Pinned" else "Pin", pinned, { e = e.togglePin(card) }, enabled = e.canPin(card))
                        }
                        GlowSwitch(shown, { e = e.toggleHidden(card) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton("Cancel", onDismiss, Modifier.weight(1f))
            PageButton("Done", { onSave(e) }, Modifier.weight(1f), primary = true)
        }
    }
}

/** Keeps [TodayEdits] in a saved state bundle as the hidden and the pinned cards' names, pins in their order. */
internal val TodayEditsSaver: Saver<TodayEdits, Any> = listSaver(
    save = { e -> listOf(ArrayList(e.hidden.map { it.name }), ArrayList(e.pinned.map { it.name })) },
    restore = { v ->
        @Suppress("UNCHECKED_CAST")
        fun cards(i: Int) = (v[i] as List<String>).map(TodayCard::valueOf)
        TodayEdits(cards(0).toSet(), cards(1))
    },
)
