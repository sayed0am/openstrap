package org.openstrap.app.ui.glow

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class GlowGridTest {
    private fun pack(vararg sizes: CardSize) = packGrid(sizes.mapIndexed { i, s -> s to i })

    @Test fun `full width sizes take a row each`() {
        assertEquals(
            listOf(GridRow.Full(CardSize.Large, 0), GridRow.Full(CardSize.Wide, 1), GridRow.Full(CardSize.Strip, 2), GridRow.Full(CardSize.List, 3)),
            pack(CardSize.Large, CardSize.Wide, CardSize.Strip, CardSize.List),
        )
    }

    @Test fun `two smalls make a pair`() {
        assertEquals(listOf(GridRow.Pair(0, 1)), pack(CardSize.Small, CardSize.Small))
    }

    @Test fun `a lone small becomes a wide`() {
        assertEquals(listOf(GridRow.Full(CardSize.Wide, 0)), pack(CardSize.Small))
        assertEquals(
            listOf(GridRow.Full(CardSize.Wide, 0), GridRow.Full(CardSize.Large, 1)),
            pack(CardSize.Small, CardSize.Large),
        )
    }

    @Test fun `three smalls make a pair and a wide`() {
        assertEquals(listOf(GridRow.Pair(0, 1), GridRow.Full(CardSize.Wide, 2)), pack(CardSize.Small, CardSize.Small, CardSize.Small))
    }

    @Test fun `a tall takes the two smalls after it`() {
        assertEquals(listOf(GridRow.TallPair(0, 1, 2)), pack(CardSize.Tall, CardSize.Small, CardSize.Small))
        assertEquals(
            listOf(GridRow.TallPair(0, 1, 2), GridRow.Full(CardSize.Wide, 3)),
            pack(CardSize.Tall, CardSize.Small, CardSize.Small, CardSize.Small),
        )
    }

    @Test fun `a tall without two smalls after it becomes a wide`() {
        assertEquals(listOf(GridRow.Full(CardSize.Wide, 0)), pack(CardSize.Tall))
        assertEquals(listOf(GridRow.Full(CardSize.Wide, 0), GridRow.Full(CardSize.Wide, 1)), pack(CardSize.Tall, CardSize.Small))
        assertEquals(
            listOf(GridRow.Pair(0, 1), GridRow.Full(CardSize.Wide, 2)),
            pack(CardSize.Small, CardSize.Small, CardSize.Tall),
        )
    }

    @Test fun `order is kept`() {
        assertEquals(
            listOf(GridRow.Full(CardSize.Large, 0), GridRow.Pair(1, 2), GridRow.TallPair(3, 4, 5), GridRow.Full(CardSize.Strip, 6), GridRow.Full(CardSize.Wide, 7)),
            pack(CardSize.Large, CardSize.Small, CardSize.Small, CardSize.Tall, CardSize.Small, CardSize.Small, CardSize.Strip, CardSize.Small),
        )
        assertEquals(emptyList<GridRow<Int>>(), pack())
    }

    @Test fun `unit width on a phone`() {
        // (390 - 32 - 12) / 2, the mockups' 173.
        assertEquals(173f, GlowGrid.unitWidthIn(358.dp).value, 1e-4f)
    }

    @Test fun `heights line up across rows`() {
        // A tall is exactly two smalls and the gap between them.
        assertEquals(332f, GlowGrid.height(CardSize.Tall)!!.value, 1e-4f)
        assertEquals(GlowGrid.height(CardSize.Large), GlowGrid.height(CardSize.Tall))
        assertEquals(68f, GlowGrid.height(CardSize.Strip)!!.value, 1e-4f)
        assertEquals(null, GlowGrid.height(CardSize.List))
    }
}
