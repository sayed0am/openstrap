package org.openstrap.app.ui.glow

import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import org.openstrap.app.presentation.TimeOfDay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import org.openstrap.app.ui.components.GlassSheet
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import java.time.ZoneId
import org.openstrap.app.ui.theme.ControlSurface

/*
 * The settings pages (the Strap page and what it opens, Profile, You) in the glow language's quiet tier: black, with
 * grouped lists in place of Material panels, a round back button, and plain buttons. One look for every page, so the
 * app doesn't turn into another product at the strap's boundary.
 */

private val PanelShape = GlowLayout.CardShape
private val PanelSurface = ListSurface
private val Hairline = Color.White.copy(alpha = 0.05f)
/** A [PageRow] whose page is open beside the list. */
private val SelectedTint = Color.White.copy(alpha = 0.08f)

/**
 * The one row or button that can't be undone (Forget, Delete all, Delete workout): plain white at 72%, no colour, since
 * colour in the app means a metric or the strap's state (spec 2.2). The confirmation asks before anything goes.
 */
val DestructiveInk = Color.White.copy(alpha = 0.72f)

/** The round back button of every page over a tab (spec 3.4). */
val BackButtonSize = 44.dp

/** The page header's height: the back button with room round it. */
val PageHeaderHeight = 64.dp

/**
 * A page's way back (spec 3.4): a [BackButtonSize] round button on the [ControlSurface] with a 20 dp arrow, dimmed
 * and still while not [enabled] (a firmware install running).
 */
@Composable
fun BackButton(onBack: () -> Unit, enabled: Boolean = true) =
    RoundIconButton(
        AppIcons.ArrowLeft, stringResource(R.string.glow_back), onBack, size = BackButtonSize, surface = ControlSurface, iconSize = 20.dp,
        enabled = enabled,
    )

/** How much of a [RoundIconButton] its icon takes by default. */
private const val ROUND_ICON_SHARE = 0.47f

/**
 * A round button holding just an [icon]: [size] across on [surface] (10% white by default), the icon [iconSize]
 * across in [tint]. [label] is read out, [clickLabel] says what a tap does when that's different, and [dot] marks the
 * top right with a [NoticeDot]. Back, (i), close and the header's avatar are all this button.
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    surface: Color = Color.White.copy(alpha = 0.10f),
    iconSize: Dp = size * ROUND_ICON_SHARE,
    tint: Color = Color.White,
    clickLabel: String = label,
    enabled: Boolean = true,
    dot: Boolean = false,
) {
    Box(modifier.size(size)) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(surface).alpha(if (enabled) 1f else 0.4f)
                .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(iconSize), tint = tint) }
        // On the circle's edge at its top right, ringed in the page's black so it reads apart from the button.
        if (dot) NoticeDot(Modifier.align(Alignment.TopEnd), ring = Color.Black)
    }
}

/**
 * The one page header (spec 3.4), on every page over a tab, details included: the [BackButton] and the [title] in
 * Page title type, over the day it's about or a short note ([subtitle]) in Secondary when there is one, then optional
 * [trailing] content (Save, a detail's (i)). [onTitle] makes the title a button with a caret (a detail's switcher).
 */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    subtitle: String? = null,
    onTitle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val switchLabel = stringResource(R.string.glow_page_switch)
    Row(Modifier.fillMaxWidth().height(PageHeaderHeight), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack, backEnabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .let { if (onTitle != null) it.clickable(onClickLabel = switchLabel, role = Role.Button, onClick = onTitle) else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = Color.White, style = GlowType.pageTitle, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (onTitle != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(AppIcons.CaretDown, null, tint = Color.White.copy(alpha = GlowType.CAPTION_ALPHA), modifier = Modifier.size(16.dp))
                }
            }
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A grouped panel for free content (text, fields, buttons): a [QuietPanel] with an optional [title] row and [note]
 * on its right ([GlowHeader]).
 */
@Composable
fun PagePanel(modifier: Modifier = Modifier, title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) =
    QuietPanel(modifier) {
        if (title != null) GlowHeader(title, note)
        content()
    }

/** A grouped list of [PageRow]s, without a title row (the rows carry the names). */
@Composable
fun PageRows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        // Clipped, so a selected row's tint keeps to the panel's corners.
        modifier.fillMaxWidth().clip(PanelShape).background(PanelSurface, PanelShape).border(1.dp, CardHairline, PanelShape)
            .padding(GlowLayout.ListPadding),
        content = content,
    )
}

/**
 * A settings row: the [title], and its current [value] either on the right ([below] false, the Strap page) or as a
 * second line ([below] true, the You page). The title stays on one line (spec 2.3): where it and a value on the right
 * don't both fit on one line (a narrow column beside a pane), the value drops under it ([RowNameValue]).
 * [trailing] replaces the value on the right (a switch). [divider] draws
 * the hairline above it. [icon] sits quietly on the left, centred on the row, with the title and value lined up after it.
 * A row that opens something ([onClick], no [trailing]) ends in the tap caret (spec 2.5). [selected] marks the row
 * whose page is open beside the list on a wide screen: tinted across the panel's width. [dot] puts a [NoticeDot]
 * after the title of a [below] row: something new waits behind it.
 */
@Composable
fun PageRow(
    title: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    below: Boolean = false,
    divider: Boolean = true,
    titleColor: Color = Color.White,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    selected: Boolean = false,
    dot: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .let {
                if (!selected) it else it.semantics { this.selected = true }.drawBehind {
                    val inset = GlowLayout.CardInset.toPx()
                    drawRect(SelectedTint, Offset(-inset, 0f), Size(size.width + 2 * inset, size.height))
                }
            }
            .let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
            .topHairline(divider, Hairline)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RowIcon(icon)
        if (below) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (dot) Row(verticalAlignment = Alignment.CenterVertically) {
                RowName(title, titleColor)
                Spacer(Modifier.width(8.dp))
                NoticeDot()
            } else RowName(title, titleColor)
            if (value != null) Text(value, color = RowValueColor, style = GlowType.secondary)
        } else RowNameValue(title, titleColor, value.takeIf { trailing == null }, Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(RowCaretGap))
            TapCaret(ROW_CARET_ALPHA)
        }
    }
}

/** A settings row's [icon], quiet on the left and centred on the row, with the gap to its title after it. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(Modifier.padding(end = 14.dp).width(20.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.7f))
    }
}

/**
 * Something new waits behind what it sits on (a new version, on the avatar and the Updates row): a small white dot,
 * the size of the strap chip's, with a [ring] of the background around it where it overlaps a button's edge.
 */
@Composable
fun NoticeDot(modifier: Modifier = Modifier, ring: Color? = null) {
    if (ring == null) Box(modifier.size(7.dp).background(Color.White, CircleShape))
    else Box(modifier.size(11.dp).background(ring, CircleShape).padding(2.dp).background(Color.White, CircleShape))
}

/** A row's value in Secondary, on the right or under the name. */
private val RowValueColor = Color.White.copy(alpha = 0.6f)

/** A row's name in Body, on one line (spec 2.3): ellipsized only when it alone is wider than the row. */
@Composable
internal fun RowName(name: String, color: Color = Color.White) =
    Text(name, color = color, style = GlowType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Between a row's name and the value on its right. */
private val RowValueGap = 12.dp
/** Between a row's name and the value dropped under it. */
private val RowLineGap = 3.dp

/**
 * Whether a row's value sits on the right of its name ([nameWidth] and [valueWidth] each on one line) in a row
 * [available] wide with [gap] between them. When not, it drops under the name, so the name never wraps.
 */
internal fun valueBeside(nameWidth: Int, valueWidth: Int, available: Int, gap: Int): Boolean = nameWidth + gap + valueWidth <= available

/**
 * A row's name with its [value] on the right, on the name's baseline, when both fit on one line each
 * ([valueBeside]), otherwise the value under the name in Secondary, up to two lines and then ellipsized. The name
 * keeps to one line either way.
 */
@Composable
internal fun RowNameValue(name: String, nameColor: Color, value: String?, modifier: Modifier = Modifier) {
    Layout(
        content = {
            RowName(name, nameColor)
            if (value != null) Text(value, color = RowValueColor, style = GlowType.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, cs ->
        val width = if (cs.hasBoundedWidth) cs.maxWidth else measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val loose = Constraints(maxWidth = width)
        val nameP = measurables[0].measure(loose)
        val v = measurables.getOrNull(1)
        if (v == null) return@Layout layout(width, nameP.height) { nameP.place(0, 0) }
        val gap = RowValueGap.roundToPx()
        if (valueBeside(measurables[0].maxIntrinsicWidth(Constraints.Infinity), v.maxIntrinsicWidth(Constraints.Infinity), width, gap)) {
            val valueP = v.measure(Constraints(maxWidth = (width - nameP.width - gap).coerceAtLeast(0)))
            // The value on the name's baseline, at the row's end.
            val shift = nameP[FirstBaseline] - valueP[FirstBaseline]
            val top = minOf(0, shift)
            val height = maxOf(nameP.height, valueP.height + shift) - top
            layout(width, height) {
                nameP.place(0, -top)
                valueP.place(width - valueP.width, shift - top)
            }
        } else {
            val valueP = v.measure(loose)
            val line = RowLineGap.roundToPx()
            layout(width, nameP.height + line + valueP.height) {
                nameP.place(0, 0)
                valueP.place(0, nameP.height + line)
            }
        }
    }
}

/**
 * A setting picked from a short list: the [title] and the chosen value on the right, then a down caret. A tap opens a
 * menu at the row's end with every option and a tick on the chosen one; picking one calls [onPick] and closes it.
 * [note] is a line under the row (shown only when something needs saying). Takes the place of a row of chips.
 */
@Composable
fun <T> PageMenuRow(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onPick: (T) -> Unit,
    divider: Boolean = true,
    enabled: Boolean = true,
    note: String? = null,
    quiet: (T) -> Boolean = { false },
) {
    var open by remember { mutableStateOf(false) }
    val changeLabel = stringResource(R.string.glow_page_change)
    Column(
        Modifier.fillMaxWidth()
            .topHairline(divider, Hairline),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = enabled, onClickLabel = changeLabel, role = Role.DropdownList) { open = true }
                    .alpha(if (enabled) 1f else 0.45f)
                    .padding(vertical = GlowLayout.RowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val value = selected?.let(label)
                Text(title, color = Color.White, style = GlowType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (value != null) {
                    Spacer(Modifier.width(RowValueGap))
                    Text(
                        value, style = GlowType.secondary, maxLines = 1,
                        color = if (selected.let(quiet)) Color.White.copy(alpha = 0.4f) else RowValueColor,
                    )
                }
                Spacer(Modifier.width(RowCaretGap))
                Icon(AppIcons.CaretDown, null, tint = Color.White.copy(alpha = ROW_CARET_ALPHA), modifier = Modifier.size(CaretSize))
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                DropdownMenu(
                    expanded = open, onDismissRequest = { open = false },
                    offset = DpOffset(0.dp, 4.dp), shape = MenuShape, containerColor = MenuSurface,
                    tonalElevation = 0.dp, shadowElevation = 12.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                ) {
                    options.forEach { o ->
                        val on = o == selected
                        DropdownMenuItem(
                            text = { Text(label(o), style = GlowType.body, color = if (on) Color.White else RowValueColor) },
                            onClick = { open = false; if (!on) onPick(o) },
                            trailingIcon = if (on) ({ Icon(AppIcons.Tick02, null, tint = Color.White, modifier = Modifier.size(18.dp)) }) else null,
                            modifier = Modifier.widthIn(min = 180.dp).semantics { this.selected = on },
                        )
                    }
                }
            }
        }
        if (note != null) Text(note, color = RowValueColor, style = GlowType.secondary, modifier = Modifier.padding(bottom = GlowLayout.RowPadding))
    }
}

private val MenuShape = RoundedCornerShape(18.dp)
private val MenuSurface = Color(0xFF232326)

/**
 * A row with a switch: the [title], an optional [subtitle] under it, and the switch on the right. A tap anywhere on
 * the row flips it, so a long name is as easy to hit as the switch. [icon] sits on the left as on a [PageRow].
 */
@Composable
fun PageSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    divider: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .topHairline(divider, Hairline)
            .padding(vertical = GlowLayout.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RowName(title)
            if (subtitle != null) Text(subtitle, color = RowValueColor, style = GlowType.secondary)
        }
        Spacer(Modifier.width(12.dp))
        // The row is the switch to a screen reader, so the knob isn't read a second time.
        GlowSwitch(checked, onChange, Modifier.clearAndSetSemantics {}, enabled = enabled)
    }
}

/** How big a [PageButton] is: its height, side padding, icon and type. */
enum class ButtonSize(val height: Dp, val padding: Dp, val icon: Dp) {
    /** A page's own buttons. */
    Page(52.dp, 18.dp, 20.dp),

    /** On a hero card, like Start or Try again. */
    Hero(48.dp, 18.dp, 16.dp),

    /** Inside a row, like Save beside a found workout. */
    Row(32.dp, 14.dp, 14.dp),
}

/**
 * A page's button, fully round (spec 2.4): white with black text when it's the page's main action ([primary]),
 * otherwise on the Control surface with white text, or on glass when it sits [onCard] and its colour shows through.
 * Body type (Secondary at [ButtonSize.Row]), an [icon] before the text when given.
 */
@Composable
fun PageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    color: Color = if (primary) Color.Black else Color.White,
    icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Page,
    onCard: Boolean = false,
) {
    Row(
        // Dimmed before the pill is drawn, so a disabled button fades whole, pill and label (user, 2026-11-03).
        modifier.height(size.height).alpha(if (enabled) 1f else 0.4f).clip(CircleShape)
            .let {
                when {
                    primary -> it.background(Color.White)
                    onCard -> it.background(Color.White.copy(alpha = 0.08f)).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    else -> it.background(ControlSurface)
                }
            }
            .let { if (enabled) it.pressClickable(onClick, CircleShape) else it }
            .semantics { role = Role.Button }
            .padding(horizontal = size.padding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(size.icon), tint = color)
            Spacer(Modifier.width(8.dp))
        }
        val style = if (size == ButtonSize.Row) GlowType.secondary else GlowType.body
        Text(text, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A quiet action that is only its words, in Body at 72% (Delete under Save, Reset): no surface, so the page's buttons
 * stay the loudest thing. At least 44 dp tall to tap.
 */
@Composable
fun PageTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
) {
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, style = GlowType.body, maxLines = 1) }
}

/**
 * A text field in the tile style (spec 2.4): the Control surface with 16 dp corners, the [label] over it in
 * Secondary, the text in Body, an optional [unit] after it. [error] draws an amber hairline round it.
 */
@Composable
fun PageField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    placeholder: String? = null,
    error: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.alpha(if (enabled) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(GlowLayout.TileShape).background(ControlSurface)
                .let { if (error) it.border(1.dp, MetricColors.warn, GlowLayout.TileShape) else it }
                .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = Color.White.copy(alpha = 0.3f), style = GlowType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value, onValueChange, Modifier.fillMaxWidth(), enabled = enabled,
                    textStyle = GlowType.body.copy(color = Color.White), singleLine = true,
                    keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
                    cursorBrush = SolidColor(Color.White),
                )
            }
            if (unit != null) {
                Spacer(Modifier.width(8.dp))
                Text(unit, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
            }
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }
}

/**
 * A thin progress bar: a 4 dp track at 10% white, filled white to [fraction]. With no [fraction] (how long isn't
 * known), a third of the track slides across.
 */
@Composable
fun PageProgress(fraction: Float? = null, modifier: Modifier = Modifier.fillMaxWidth()) {
    val slide = if (fraction == null) {
        rememberInfiniteTransition(label = "page progress")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "page progress slide")
    } else null
    Canvas(modifier.height(4.dp).clip(CircleShape)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = r)
        if (fraction != null) {
            drawRoundRect(Color.White, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
        } else if (slide != null) {
            val w = size.width / 3f
            drawRoundRect(Color.White, topLeft = Offset(-w + (size.width + w) * slide.value, 0f), size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/**
 * A sheet over the page (spec 2.5, sheets): the glass sheet with its handle, the [title] as a card title, an
 * optional [note] under it, then [content], [side] from the edges (narrower for a sheet of grouped lists, which
 * bring their own inset). It scrolls when taller than most of the screen. Without a [title] the content starts at
 * the top, for a sheet that heads itself (the calendar's month, the info sheet's name).
 */
@Composable
fun PageSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    note: String? = null,
    side: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    GlassSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = side).navigationBarsPadding().padding(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (title != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, style = GlowType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(note, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            }
            content()
        }
    }
}

/**
 * A question before something that can't be taken back (Forget, Disconnect, Delete all): a [PageSheet] with the
 * [text], any extra [content], then Cancel and the [confirm] button, the white one, side by side.
 */
@Composable
fun PageConfirm(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = stringResource(R.string.glow_cancel),
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    PageSheet(onDismiss, title) {
        if (text != null) Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
        content?.invoke(this)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PageButton(dismiss, onDismiss, Modifier.weight(1f))
            PageButton(confirm, onConfirm, Modifier.weight(1f), enabled = confirmEnabled, primary = true)
        }
    }
}

/**
 * A chip (spec 2.4, fully round): a hairline and 72% text, or white with black text when [selected]. A [filled] chip
 * sits on the Control surface instead of the hairline, for a lone chip like Live. Secondary type, at least 36 dp tall.
 * A [description] is what TalkBack reads in place of a [text] too short to say it, like a day's letter.
 */
@Composable
fun PageChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    filled: Boolean = false,
    description: String? = null,
) {
    Box(
        modifier.heightIn(min = 36.dp).clip(CircleShape)
            .background(if (selected) Color.White else if (filled) ControlSurface else Color.Transparent)
            .let { if (selected || filled) it else it.border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape) }
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = role, onClick = onClick)
            .semantics {
                this.selected = selected
                description?.let { contentDescription = it }
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (selected) Color.Black else Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
            style = GlowType.secondary, maxLines = 1,
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/**
 * A pill that names a choice or does one thing: an [icon] in [tint] or a [dot], the [label] in [style] (Body), and a
 * [caret] when tapping opens a list. [height] tall on [surface] (the Control surface), with a [hairline] on a quiet
 * card. Without [onClick] it's only a label, like the live screen's sport or a summary's earned best. A long label
 * ends in an ellipsis and leaves the caret its room. Small pills (Done over a summary, the earned chips) take
 * Secondary.
 */
@Composable
internal fun IconPill(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Color.White,
    dot: Color? = null,
    caret: Boolean = false,
    height: Dp = 44.dp,
    surface: Color = ControlSurface,
    hairline: Boolean = false,
    description: String? = null,
    style: TextStyle = GlowType.body,
) {
    Row(
        modifier.height(height).clip(CircleShape).background(surface)
            .let { if (hairline) it.border(1.dp, RowHairline, CircleShape) else it }
            .let { if (onClick != null) it.pressClickable(onClick, CircleShape) else it }
            .semantics {
                if (onClick != null) role = Role.Button
                if (description != null) contentDescription = description
            }
            .padding(start = if (icon != null || dot != null) 12.dp else 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = tint)
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        // Weighted only beside a caret, so a squeezed label gives way to it; a weight in a scrolling row would be zero wide.
        Text(
            label, if (caret) Modifier.weight(1f, fill = false) else Modifier, color = Color.White, style = style, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caret) Icon(AppIcons.CaretDown, null, Modifier.size(14.dp), tint = Color.White)
    }
}

/**
 * A row of choices on a faint pill, the chosen one on a soft white pill: Female or Male, Day to Year, a route on or
 * off, how you feel. [selected] -1 leaves none chosen. [role] is what each choice is to a screen reader (a tab for
 * the ranges that switch a detail's chart).
 */
// Every caller builds its labels in place, so a stable list type wouldn't let this skip any more often.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
internal fun GlowSegments(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    Row(modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .pressClickable({ onSelect(i) }, CircleShape, scale = 0.97f)
                    .semantics { this.role = role; this.selected = on }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Color.White.copy(alpha = 0.6f), style = GlowType.body, maxLines = 1)
            }
        }
    }
}

/**
 * A small heading in Secondary over what it names: a panel of rows (so two panels never sit back to back), a row of
 * chips, what a workout earned. [inset] lines it up with what's under it: a panel's rows sit [PanelLabelInset] in.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, inset: Dp = 4.dp) {
    Text(
        text, modifier.padding(start = inset), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** How far a [SectionLabel] over a panel of rows sits in, near the rows' own inset. */
val PanelLabelInset = 18.dp

/**
 * A status in a few words with a coloured dot (spec 2.1, the strap's state): "Strap ready, 72 bpm", "GPS good". The
 * dot leads, or follows the words where the line is set flush right ([dotAfter], the live screen's corner).
 */
@Composable
internal fun StatusDot(text: String, dot: Color, modifier: Modifier = Modifier, dotAfter: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!dotAfter) Canvas(Modifier.size(6.dp)) { drawCircle(dot) }
        Text(text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, maxLines = 1)
        if (dotAfter) Canvas(Modifier.size(6.dp)) { drawCircle(dot) }
    }
}

/**
 * A secondary choice: a tile on the Control surface (spec 2.4) with a [caption] in Secondary over the [label] in Body,
 * and the tap caret at its end, so it reads as something that opens. As wide as it's given.
 */
@Composable
fun OptionButton(caption: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(GlowLayout.TileShape).background(ControlSurface).pressClickable(onClick, GlowLayout.TileShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(caption, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            Text(label, color = Color.White, style = GlowType.body)
        }
        Spacer(Modifier.width(RowCaretGap))
        TapCaret(ROW_CARET_ALPHA)
    }
}

/**
 * A [PageField] for a whole number, or a [decimal] one: only digits (and a point) get in, at most five, and the
 * keyboard is the number pad. As wide as it's given. An empty [label] leaves the field unlabelled, for a pair like
 * hours and minutes.
 */
@Composable
internal fun PageNumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    unit: String,
    error: Boolean,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    PageField(
        value, { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(NUMBER_FIELD_LENGTH)) },
        label.ifEmpty { null }, modifier.fillMaxWidth(), unit = unit, error = error,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

/** The most characters a [PageNumberField] takes: a weight like 158.7, or five digits. */
private const val NUMBER_FIELD_LENGTH = 5

/** A secondary line of explanation on a page. */
@Composable
fun PageNote(text: String, color: Color = Color.White.copy(alpha = 0.6f)) {
    Text(text, color = color, style = GlowType.secondary)
}

/** A list row's divider: a 1 dp [color] line along its top, when [show] (every row but the first). */
internal fun Modifier.topHairline(show: Boolean, color: Color = RowHairline): Modifier =
    if (show) drawBehind { drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else this

/** A time of day from [epoch] seconds in the phone's zone, "07:45" or "7:45 AM" by the phone's setting. */
internal fun clock(epoch: Long): String = TimeOfDay.format(epoch, ZoneId.systemDefault())
