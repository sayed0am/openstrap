package org.openstrap.app.ui.glow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openstrap.app.R
import org.openstrap.app.data.RecentChip
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.dashboard.ScreenList
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.ui.theme.SportIcons
import org.openstrap.app.ui.workout.familyColor

/* ---------------------------------------------- Choose ----------------------------------------------- */

/** Your usual sport on Choose, with its one line: what you did last time. */
data class ChooseSport(val type: Int, val line: String)

/**
 * Choose (workouts spec 2.1), the first screen of a workout and every sport on it, so one tap from Activity's All
 * sports reaches them all: "Start a workout" and the strap in one line; your usual as a slim unlit row with Start, which starts with last time's settings and skips Ready ([onStartUsual]),
 * while the row elsewhere opens Ready ([onReady]); the search over every sport; Recent as chips, saved workouts among
 * them ([onSaved]); then all sports as tiles and More sports, as the picker has them. A new user has no usual, and
 * Run, Walk and Strength as recent.
 */
@Composable
fun GlowChooseScreen(
    usual: ChooseSport?,
    recent: List<RecentChip>,
    connection: ConnectionState,
    liveHeartRate: Int?,
    contentPadding: PaddingValues,
    onStartUsual: (Int) -> Unit,
    onReady: (Int) -> Unit,
    onBack: () -> Unit,
    onSaved: (RecentChip.Saved) -> Unit = {},
) {
    var more by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (searching) searching = false else if (more) more = false else onBack() }
    if (more) {
        MoreSports(contentPadding, onReady) { more = false }
        return
    }
    if (searching) {
        SearchPage(contentPadding, onReady) { searching = false }
        return
    }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(AppIcons.X, stringResource(R.string.glow_close), onBack)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.glow_choose_title), color = Color.White, style = GlowType.pageTitle, maxLines = 1)
        }
    }
    val choose: @Composable ColumnScope.() -> Unit = {
        StrapLine(connection, liveHeartRate)
        if (usual != null) UsualRow(usual, { onStartUsual(usual.type) }, { onReady(usual.type) })
        // The search opens a page of its own (board ActivitySearchTyping).
        SearchEntry(stringResource(R.string.glow_choose_search_all)) { searching = true }
        if (recent.isNotEmpty()) {
            SectionLabel(stringResource(R.string.glow_choose_recent))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { chip ->
                    when (chip) {
                        is RecentChip.Sport -> RecentPill(chip.type, Workouts.shortName(chip.type)) { onReady(chip.type) }
                        is RecentChip.Saved -> RecentPill(chip.sport, chip.name) { onSaved(chip) }
                    }
                }
            }
        }
        SportTiles(stringResource(R.string.glow_choose_all_sports), Workouts.EVERYDAY, Workouts::shortName, { false }, onReady)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Unfolded (spec 2.4): the rest of the sports, A to Z, in a column of their own beside the tiles, each side
        // scrolling by itself, so none is a tap away behind More sports.
        if (maxWidth >= GlowLayout.TwoColumnWidth) CompositionLocalProvider(LocalPageColumn provides GlowLayout.MaxContentWidth) {
            WorkoutPage(Color.Transparent, contentPadding, scroll = false) {
                header()
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlowLayout.ColumnGap * 2)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = choose)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(stringResource(R.string.glow_choose_more_sports))
                        MoreSportsList(onReady)
                    }
                }
            }
        } else WorkoutPage(Color.Transparent, contentPadding) {
            header()
            choose()
            MoreSportsRow { more = true }
        }
    }
}

/**
 * Your usual: a slim unlit card with its name, what you did last time, and Start at its end. The row itself opens
 * Ready.
 */
@Composable
private fun UsualRow(s: ChooseSport, onStart: () -> Unit, onOpen: () -> Unit) {
    val color = familyColor(Workouts.family(s.type))
    val haptics = org.openstrap.app.ui.components.rememberHaptics()
    PictureCard(color, Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.glow_choose_your_usual), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary,
                )
                Text(
                    Workouts.sportName(s.type), color = Color.White, style = GlowType.headerDate, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (s.line.isNotEmpty()) Text(
                    s.line, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            PageButton(
                stringResource(R.string.glow_choose_start),
                { haptics.perform(org.openstrap.app.ui.components.HapticEvent.WorkoutStart); onStart() },
                primary = true, icon = AppIcons.PlayFill,
            )
        }
    }
}

/** Choose's quiet chips, a step off black. */
private val QuietCard = ListSurface

/** A Recent chip: the sport's icon in its colour and a name, a sport's or a saved workout's, on a quiet pill. */
@Composable
private fun RecentPill(sport: Int, name: String, onClick: () -> Unit) =
    IconPill(name, onClick, icon = SportIcons.of(sport), tint = familyColor(Workouts.family(sport)), surface = QuietCard, hairline = true)

/* --------------------------------------------- Sport picker -------------------------------------------- */

/**
 * A sport's tile (spec 6.3), in the picker and on Activity's Start a workout: a 64 dp Control tile with 16 dp corners,
 * the [icon] at 24 dp, the [name] in Secondary under it. The chosen sport's tile is white with a black icon; an
 * [outdoor] sport carries the 12 dp location mark in its corner.
 */
@Composable
internal fun SportTile(
    icon: ImageVector,
    name: String,
    modifier: Modifier,
    outdoor: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    // Tight corners and room underneath, so the press area never clips a label that fills the width.
    Column(
        modifier.pressClickable(onClick, RoundedCornerShape(8.dp)).semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(SportTileSize).clip(GlowLayout.TileShape).background(if (selected) Color.White else Raised),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (selected) Color.Black else Color.White
            Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
            if (outdoor) Icon(
                SportIcons.Location, stringResource(R.string.glow_choose_outdoors), tint = ink.copy(alpha = 0.7f),
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
 * Choose a sport (spec 6.3), for Ready's sport and for saving a stretch nobody started: a page with the search field,
 * your Recent sports, the twelve everyday ones as tiles and a More sports row for the rest, A to Z. Search looks
 * through every sport, both kinds of a two-kind sport included. A tile picks its sport's key ([Workouts.tileType]);
 * Ready's switch then picks the kind. [selected] is the sport chosen now, -1 for none. Choose has the same parts.
 */
@Composable
internal fun SportPicker(selected: Int, recent: List<Int>, contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (more) more = false else onBack() }
    if (more) {
        MoreSports(contentPadding, onPick) { more = false }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val chosen = Workouts.tileType(selected)
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.glow_choose_pick_title), onBack) }
        item { SearchField(query, stringResource(R.string.glow_choose_search)) { query = it } }
        if (query.isNotBlank()) {
            item(key = "found") { SearchResults(query, selected, onPick) }
            return@ScreenList
        }
        if (recent.isNotEmpty()) item(key = "recent") {
            SportTiles(stringResource(R.string.glow_choose_recent), recent, Workouts::shortName, { it == chosen }, onPick)
        }
        item(key = "sports") {
            SportTiles(stringResource(R.string.glow_choose_sports), Workouts.EVERYDAY, Workouts::shortName, { it == chosen }, onPick)
        }
        item(key = "more") { MoreSportsRow { more = true } }
    }
}

/** The rest of the sports, A to Z, as a list page of their own. */
@Composable
private fun MoreSports(contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit) {
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item { PageHeader(stringResource(R.string.glow_choose_more_sports), onBack) }
        item { MoreSportsList(onPick) }
    }
}

/** Every sport behind the tiles, A to Z, in one panel. */
@Composable
private fun MoreSportsList(onPick: (Int) -> Unit) = ListPanel {
    Workouts.MORE.forEachIndexed { i, type ->
        PageRow(Workouts.sportName(type), icon = SportIcons.of(type), divider = i > 0, onClick = { onPick(type) })
    }
}

/** The row that opens [MoreSports]. */
@Composable
private fun MoreSportsRow(onClick: () -> Unit) = ListPanel {
    PageRow(
        stringResource(R.string.glow_choose_more_sports), stringResource(R.string.glow_choose_more_sports_detail),
        below = true, divider = false, onClick = onClick,
    )
}

/** What the search finds, as tiles, or that nothing matches [query]. Picking one puts the keyboard away. */
@Composable
private fun SearchResults(query: String, selected: Int, onPick: (Int) -> Unit) {
    val found = remember(query) { Workouts.search(query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val pick: (Int) -> Unit = { keyboard?.hide(); focus.clearFocus(); onPick(it) }
    if (found.isEmpty()) Text(
        stringResource(R.string.glow_choose_no_match, query.trim()), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
        style = GlowType.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    else SportTiles(null, found, { if (it in Workouts.EVERYDAY) Workouts.shortName(it) else Workouts.sportName(it) }, { it == selected }, pick)
}

/** Choose's search field before it's typed in: it looks like the field and opens [SearchPage]. */
@Composable
private fun SearchEntry(hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(GlowLayout.TileShape).background(Raised)
            .pressClickable(onClick, GlowLayout.TileShape).semantics { role = Role.Button }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SportIcons.Search, null, tint = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(hint, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.body, maxLines = 1)
    }
}

/**
 * The search over every sport (board ActivitySearchTyping): back and the field, with the keyboard up, then how many
 * sports match and a row for each, its icon and full name. Before anything is typed, every sport A to Z.
 */
@Composable
private fun SearchPage(contentPadding: PaddingValues, onPick: (Int) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(query) {
        if (query.isBlank()) (Workouts.EVERYDAY + Workouts.MORE).distinct().sortedBy { Workouts.sportName(it) }
        // Names starting with what's typed first, then a word in them starting with it, then the rest.
        else Workouts.search(query).sortedBy { type ->
            val name = Workouts.sportName(type).lowercase()
            val q = query.trim().lowercase()
            when {
                name.startsWith(q) -> 0
                name.split(' ').any { it.startsWith(q) } -> 1
                else -> 2
            }
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    ScreenList(contentPadding, gutter = GlowLayout.Gutter) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    SearchField(query, stringResource(R.string.glow_choose_search_all), Modifier.focusRequester(focus)) { query = it }
                }
            }
        }
        item {
            Text(
                when {
                    query.isBlank() -> stringResource(R.string.glow_choose_all_sports)
                    found.isEmpty() -> stringResource(R.string.glow_choose_no_match, query.trim())
                    else -> pluralStringResource(R.plurals.glow_choose_sports_count, found.size, found.size)
                },
                color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (found.isNotEmpty()) item {
            ListPanel {
                found.forEachIndexed { i, type ->
                    PageRow(Workouts.sportName(type), icon = SportIcons.of(type), divider = i > 0, onClick = { keyboard?.hide(); onPick(type) })
                }
            }
        }
    }
}

/** A section of sport tiles, four to a row, under its Caption [title]. */
@Composable
private fun SportTiles(title: String?, types: List<Int>, name: (Int) -> String, isSelected: (Int) -> Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) SectionLabel(title)
        types.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                row.forEach { type ->
                    val outdoor = type in Workouts.OUTDOOR_ONLY
                    SportTile(SportIcons.of(type), name(type), Modifier.weight(1f), outdoor, isSelected(type)) { onPick(type) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The search on Choose and the picker: a 48 dp field on the Control surface with 16 dp corners and the search icon (spec 6.3). */
@Composable
private fun SearchField(query: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        query, onChange, modifier.fillMaxWidth(), singleLine = true,
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
                    if (query.isEmpty()) Text(hint, color = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), style = GlowType.body, maxLines = 1)
                    field()
                }
                if (query.isNotEmpty()) Icon(
                    AppIcons.X, stringResource(R.string.glow_choose_clear), tint = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onChange("") }.padding(6.dp),
                )
            }
        },
    )
}
