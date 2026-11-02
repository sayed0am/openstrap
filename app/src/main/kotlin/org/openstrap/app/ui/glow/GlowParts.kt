package org.openstrap.app.ui.glow

import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.glow.draw.DotFont
import org.openstrap.app.ui.glow.draw.dotLayout
import org.openstrap.app.ui.glow.draw.drawDots
import org.openstrap.app.ui.glow.draw.DotSize

/* ------------------------------------------------------------------------------------------------ */
/* Dot-matrix numerals                                                                               */
/* ------------------------------------------------------------------------------------------------ */

/**
 * A number drawn as round dots, like an LED matrix (the "3.3" on the reference cards), in one of the six [DotSize]s
 * for where it sits. Digits, `.`, `,`, `:`, `-`, `−`, `+` and `%` are supported.
 */
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    dot: DotSize,
    color: Color = Color.White,
    /** Shrinks the dots below [dot] where the number must fit a space (the live workout's arc), 0..1. */
    scale: Float = 1f,
) {
    // The shared dot geometry ([dotLayout]), as a widget's number draws it.
    val dotDp = (dot.dot * scale).value
    val box = dotLayout(text, dotDp, 1f)
    Canvas(
        modifier
            .size(box.width.dp, box.height.dp)
            .semantics { contentDescription = text },
    ) {
        drawDots(dotLayout(text, dotDp, density), color)
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Switch                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/**
 * The pill switch, monochrome like the workout buttons: white with a black knob when on, dim grey when off.
 * Inside a glow card, pass the card's colour as [accent]: on, it's a deeper shade of that colour with a white knob.
 */
@Composable
fun GlowSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Color? = null) {
    val onTrack = accent?.let { lerp(it, Color.Black, 0.25f) } ?: Color.White
    val track by animateColorAsState(if (checked) onTrack else Color.White.copy(alpha = 0.12f), label = "glow switch track")
    val knobColor by animateColorAsState(
        if (!checked) Color.White.copy(alpha = 0.6f) else if (accent != null) Color.White else Color.Black,
        label = "glow switch knob colour",
    )
    val knob by animateDpAsState(if (checked) 26.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "glow switch knob")
    Box(
        modifier
            .size(60.dp, 34.dp)
            .alpha(if (enabled) 1f else 0.4f)
            // Clipped so the press ripple stays inside the pill.
            .clip(CircleShape)
            .background(track)
            .toggleable(checked, enabled = enabled, interactionSource = null, indication = ripple(color = Color.Gray), role = Role.Switch, onValueChange = onCheckedChange)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(28.dp)
                .background(knobColor, CircleShape),
        )
    }
}

/** How wide a number of [digits] digits is in [dot] dots, so a space can be sized for the widest it will show. */
internal fun dotNumberWidth(digits: Int, dot: DotSize): Dp {
    val columns = digits * DotFont.DIGIT_COLUMNS + (digits - 1).coerceAtLeast(0)
    return (dot.dot + dot.gap) * columns - dot.gap
}
