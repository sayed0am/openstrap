package org.openstrap.app.ui.glow.draw

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/*
 * The dot-matrix numerals as plain geometry: which dots a string lights and where, for a dot size. DotMatrixText draws
 * them in Compose and CanvasDraw on an android.graphics.Canvas, so a widget's number is the card's (widgets spec 6.2).
 */

/** The 5×7 dot font. Anything not listed draws as a space. */
object DotFont {
    /** Glyphs one string per row, `#` a dot. */
    private val GLYPHS: Map<Char, List<String>> = mapOf(
        '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        '.' to listOf(".", ".", ".", ".", ".", ".", "#"),
        ',' to listOf(".", ".", ".", ".", ".", "#", "#"),
        ':' to listOf(".", ".", "#", ".", ".", "#", "."),
        '-' to listOf("...", "...", "...", "###", "...", "...", "..."),
        // The real minus sign of signed values (spec 2.3), drawn as the dash is.
        '−' to listOf("...", "...", "...", "###", "...", "...", "..."),
        '+' to listOf("...", "...", ".#.", "###", ".#.", "...", "..."),
        'k' to listOf("#....", "#....", "#..#.", "#.#..", "##...", "#.#..", "#..#."),
        '%' to listOf("##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##"),
        ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
    )

    /** Rows in every glyph. */
    const val ROWS = 7

    /** Columns in a digit. */
    const val DIGIT_COLUMNS = 5

    /** The glyph for [c], a space when the font has none. */
    fun glyph(c: Char): List<String> = GLYPHS[c] ?: GLYPHS.getValue(' ')

    /** Whether [c] has a glyph of its own. */
    fun has(c: Char): Boolean = c in GLYPHS

    /** Dot columns [text] takes, with one empty column between glyphs. */
    fun columns(text: String): Int {
        val glyphs = text.map(::glyph)
        return glyphs.sumOf { it[0].length } + (glyphs.size - 1).coerceAtLeast(0)
    }
}

/**
 * Where [text]'s dots sit: [width] by [height] in dp (what DotMatrixText's box takes), and each dot's centre in px from
 * the top left, [dot] px across.
 */
@Immutable
class DotLayout(val width: Float, val height: Float, val dot: Float, val centers: List<Offset>) {
    val radius: Float get() = dot / 2
}

/**
 * [text] in dots [dot] dp across (a [DotSize]'s, times any shrink), the gap between them [DotSize.DOT_GAP] of the dot,
 * at [density] px per dp. Worked out as DotMatrixText always has, so the dots land on the same pixels.
 */
fun dotLayout(text: String, dot: Float, density: Float): DotLayout {
    val gap = dot * DotSize.DOT_GAP
    val glyphs = text.map(DotFont::glyph)
    val columns = glyphs.sumOf { it[0].length } + (glyphs.size - 1).coerceAtLeast(0)
    val pitch = dot + gap
    val d = dot * density
    val step = pitch * density
    val centers = ArrayList<Offset>()
    var x0 = 0f
    glyphs.forEach { rows ->
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, c ->
                if (c == '#') centers += Offset(x0 + col * step + d / 2, row * step + d / 2)
            }
        }
        x0 += (rows[0].length + 1) * step
    }
    return DotLayout(pitch * columns - gap, pitch * DotFont.ROWS - gap, d, centers)
}

/** [dotLayout] for one of the six [DotSize]s. */
fun dotLayout(text: String, size: DotSize, density: Float): DotLayout = dotLayout(text, size.dot.value, density)

/**
 * The six dot sizes of dot-matrix numbers (spec 2.3), by where the number sits. [DotMatrixText] takes only these, so
 * the same kind of number is the same size on every screen.
 */
enum class DotSize(val dot: Dp) {
    /** Four or more figures side by side ([FigureRow]). List rows are plain text ([GlowType.rowValue]). */
    Row(2.4.dp),

    /** Edge cards and Smalls. */
    Edge(3.dp),

    /** Talls, the second glow, glow cards that aren't the hero. */
    Second(3.6.dp),

    /** The hero's number. */
    Hero(4.5.dp),

    /** A detail's header. */
    Detail(6.dp),

    /** The live workout. */
    Live(9.dp),
    ;

    /** The space between dots: a fixed share of the dot, so every size has the same texture. */
    val gap: Dp get() = dot * DOT_GAP

    companion object {
        /** The gap between dots as a share of the dot. */
        const val DOT_GAP = 0.38f
    }
}
