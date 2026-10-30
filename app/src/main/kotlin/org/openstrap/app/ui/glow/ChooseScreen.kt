package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.workout.SportIcons

/**
 * A sport's tile in the picker (spec 6.3): a 64 dp Control tile with 16 dp corners, the icon at 24 dp, the name in
 * Secondary under it. The chosen sport's tile is white with a black icon; a sport done only outdoors carries the
 * 12 dp location mark in its corner.
 */
@Composable
private fun SportTile(type: Int, name: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    // Tight corners and room underneath, so the press area never clips a label that fills the width.
    Column(modifier.pressClickable(onClick, RoundedCornerShape(8.dp)).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(SportTileSize).clip(GlowLayout.TileShape).background(if (selected) Color.White else Raised),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (selected) Color.Black else Color.White
            Icon(SportIcons.of(type), null, tint = ink, modifier = Modifier.size(24.dp))
            if (type in Workouts.OUTDOOR_ONLY) Icon(
                SportIcons.Location, "Outdoors", tint = ink.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        // Two lines, so a long name in More sports' search isn't cut short.
        Text(
            name, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SportTileSize = 64.dp

/** The sport list on its own, for saving a stretch nobody started. */
@Composable
fun GlowSportPickerScreen(
    contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit,
    recentSports: List<Int> = emptyList(), sides: Map<Int, Boolean> = emptyMap(),
) = SportPicker(-1, recentSports, contentPadding, { onPick(pickedType(it, sides)) }, onBack)

/* ------------------------------------------- Choose a sport ------------------------------------------ */

/**
 * Choose a sport (spec 6.3): a page with the search field, your Recent sports, the twelve everyday ones as tiles and
 * a More sports row for the rest, A to Z. Search looks through every sport, both kinds of a two-kind sport included.
 * A tile picks its sport's key ([Workouts.tileType]); Ready's switch then picks the kind. [selected] is the sport
 * chosen now, -1 for none.
 */
@Composable
internal fun SportPicker(selected: Int, recent: List<Int>, contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (more) more = false else onBack() }
    if (more) {
        ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
            item { PageHeader("More sports", { more = false }) }
            item {
                ListPanel {
                    Workouts.MORE.forEachIndexed { i, type ->
                        PageRow(Workouts.sportName(type), icon = SportIcons.of(type), divider = i > 0, onClick = { onPick(type) })
                    }
                }
            }
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(query) { Workouts.search(query) }
    val chosen = Workouts.tileType(selected)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader("Choose a sport", onBack) }
        item { SearchField(query) { query = it } }
        if (query.isNotBlank()) {
            item(key = "found") {
                if (found.isEmpty()) Text(
                    "No sport matches \"${query.trim()}\".", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                else SportTiles(null, found, { if (it in Workouts.EVERYDAY) Workouts.shortName(it) else Workouts.sportName(it) }, { it == selected }, onPick)
            }
            return@ScreenList
        }
        if (recent.isNotEmpty()) item(key = "recent") { SportTiles("Recent", recent, Workouts::shortName, { it == chosen }, onPick) }
        item(key = "sports") { SportTiles("Sports", Workouts.EVERYDAY, Workouts::shortName, { it == chosen }, onPick) }
        item(key = "more") {
            ListPanel {
                PageRow("More sports", "Tennis, padel, dance and the rest", below = true, divider = false, onClick = { more = true })
            }
        }
    }
}

/** A section of sport tiles, four to a row, under its Caption [title]. */
@Composable
private fun SportTiles(title: String?, types: List<Int>, name: (Int) -> String, isSelected: (Int) -> Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) ChipLabel(title)
        types.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                row.forEach { type -> SportTile(type, name(type), Modifier.weight(1f), selected = isSelected(type)) { onPick(type) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The picker's search: a 48 dp field on the Control surface with 16 dp corners and the search icon (spec 6.3). */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        query, onChange, Modifier.fillMaxWidth(), singleLine = true,
        textStyle = GlowType.body.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(GlowLayout.TileShape).background(Raised).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SportIcons.Search, null, tint = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search", color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.body, maxLines = 1)
                    field()
                }
                if (query.isNotEmpty()) Icon(
                    AppIcons.X, "Clear", tint = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onChange("") }.padding(6.dp),
                )
            }
        },
    )
}
