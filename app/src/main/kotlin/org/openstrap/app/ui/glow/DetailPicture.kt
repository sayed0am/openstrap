package org.openstrap.app.ui.glow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * A card's own picture on the page it opens (spec 2.7, "Each metric draws its own picture"): the page draws the same
 * picture its card draws, from the same data in the same ink, so tapping the card leads somewhere that visibly
 * continues it. It sits under the headline, across the page at Wide, and never carries the card's rim: a page has no
 * card edge to light.
 */

/** The card's [picture] across the page, [height] tall, under the headline and above the rest of the day. */
@Composable
internal fun DetailPicture(modifier: Modifier = Modifier, height: Dp = DetailPictureHeight, picture: @Composable (Modifier) -> Unit) =
    picture(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).height(height))

/** How tall a card's picture stands on its page, a little over a Wide's 52 dp on Today. */
internal val DetailPictureHeight = 64.dp
