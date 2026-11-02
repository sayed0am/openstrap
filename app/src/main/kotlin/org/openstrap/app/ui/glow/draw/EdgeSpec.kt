package org.openstrap.app.ui.glow.draw

import androidx.compose.runtime.Immutable

/** Which edge of an [EdgeCard] the light comes in from. */
enum class Edge { Top, Bottom, Left, Right }

/** A value against your normal. */
enum class EdgeStatus { Usual, Above, Below }

/** What an edge card is about. With its [EdgeStatus] it decides where the light sits. */
enum class EdgeKind {
    /** A value read against your normal: resting heart rate, HRV, skin temperature. */
    Value,

    /** Progress toward a goal or your usual by now: steps, Load so far. Gets a progress bar. */
    Progress,

    /** Live, now: heart rate today. */
    Live,

    /** About earlier: Recovery after the morning, Habit Age. */
    Earlier,

    /** Sleep: last night, regularity, breathing disturbances. Lit from the top, like a night sky. */
    Sleep,

    /** A level, like Charge: the light fills from the left across to the value, in place of a progress bar. */
    Level,
}

/** Where an edge card's light sits and how it's drawn. */
@Immutable
data class EdgeLight(
    val edge: Edge,
    /** Off your normal: a deeper, stronger glow. */
    val strong: Boolean,
    /** A 4 dp progress bar inside the card. */
    val progressBar: Boolean,
)

/**
 * Where the light sits for a card of [kind] whose value is [status] (spec 2.4). A plain value lights the bottom when
 * usual or below your normal and the top when above. The other kinds keep their own edge (progress bottom, live
 * right, earlier left, sleep top). Off your normal, any of them glows stronger.
 */
fun edgeLight(status: EdgeStatus, kind: EdgeKind = EdgeKind.Value): EdgeLight {
    val off = status != EdgeStatus.Usual
    val edge = when (kind) {
        EdgeKind.Value -> if (status == EdgeStatus.Above) Edge.Top else Edge.Bottom
        EdgeKind.Progress -> Edge.Bottom
        EdgeKind.Live -> Edge.Right
        EdgeKind.Earlier -> Edge.Left
        EdgeKind.Sleep -> Edge.Top
        EdgeKind.Level -> Edge.Left
    }
    return EdgeLight(edge, strong = off, progressBar = kind == EdgeKind.Progress)
}
