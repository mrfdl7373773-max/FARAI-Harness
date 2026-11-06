package dev.harness.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * The FARAI fox drawn straight from its pixel grid, with no tile behind it, so it sits on any
 * background (the white launch screen). L = body, D = inner ear/tail shade, E = eyes.
 */
object FoxPixels {
    val rows = listOf(
        "....L............L",
        "....LL..........LL",
        "....LLL........LLL",
        "....LDLL......LLDL",
        "....LDDLL....LLDDL",
        "....LDDDLL..LLDDDL",
        "....LLLLLLLLLLLLLL",
        "....LLLLLLLLLLLLLL",
        "....LLLLLLLLLLLLLL",
        "....LLLLLLLLLLLLLL",
        "....LLLLEELLLLEELL",
        "....LLLLEELLLLEELL",
        "....LLLLLLLLLLLLLL",
        "....LLLLLLLLLLLLLL",
        "....LLLLLLLLLLLLLL",
        "L...LLLLLLLLLLLL..",
        "LL..LLLLLLLLLLLL..",
        "LLD.LLLLLLLLLLLL..",
        "LDLLLLLLLLLLLLLL..",
        ".LLLLLLLLLLLLLLL..",
        "..LLLLLLLLLLLLLL..",
        "......LL....LL....",
        "......LL....LL....",
        "......LL....LL...."
    )
    val width = rows.maxOf { it.length }
    val height = rows.size
    val body = Color(0xFF48D5AA)
    val shade = Color(0xFF36BF93)
    val eye = Color(0xFF0E1116)
}

/** [size] is the fox's height; the width follows the grid (18 by 24 cells). */
@Composable
fun FoxMark(size: Dp, modifier: Modifier = Modifier, eyes: Color = FoxPixels.eye) {
    val cellDp = size / FoxPixels.height
    Canvas(modifier.size(cellDp * FoxPixels.width, size)) {
        val cell = this.size.height / FoxPixels.height
        // Slight overdraw closes hairline seams between neighbouring cells.
        val px = Size(cell + 0.6f, cell + 0.6f)
        FoxPixels.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val color = when (ch) { 'L' -> FoxPixels.body; 'D' -> FoxPixels.shade; 'E' -> eyes; else -> null }
                if (color != null) drawRect(color, Offset(x * cell, y * cell), px)
            }
        }
    }
}
