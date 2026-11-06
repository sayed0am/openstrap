package org.openstrap.app.ui.glow.draw

import androidx.compose.runtime.Immutable

/**
 * Where a picture card's rim (spec 2.5, PictureCard in the app, PictureWidget on the home screen) is lit (spec 2.5, Picture card): the point [x], [y] as fractions of the card (0,0 top
 * left), next to the part of the picture that is off. The rim fades out over [reachX] of the card's width and [reachY]
 * of its height from there.
 */
@Immutable
data class Rim(val x: Float, val y: Float, val reachX: Float = 0.6f, val reachY: Float = 0.7f) {
    companion object {
        /** The right end, halfway down: the latest part of a line or a level past its band. */
        val End = Rim(1f, 0.5f, reachY = 1.1f)

        /** The top edge, centred: a dial's top. */
        val Top = Rim(0.5f, 0f, reachX = 0.7f, reachY = 0.8f)

        /** The bottom right corner: the latest of a week drawn along the foot. */
        val Corner = Rim(1f, 1f)
    }
}
