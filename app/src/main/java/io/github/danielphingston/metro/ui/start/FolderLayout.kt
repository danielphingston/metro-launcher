package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.theme.Metro

/**
 * Where everything sits on the start screen. [tiles] are the top-level tiles in list order. With a
 * folder open, [children] are its children and [region] the cells they fill; the tiles below the
 * folder's rows are [tiles] shifted down by the region's height.
 */
class StartLayout(
    val tiles: List<Placement>,
    val children: List<Placement>,
    val region: Placement?,
    val rows: Int,
)

object FolderLayout {
    /**
     * Takes the settled [base] placements (see [TilePlacement.layout]) and, when the tile at
     * [openIndex] is an open folder, opens a full-width region for its [childSizes] right under
     * the rows it occupies; the children are packed in list order. Tiles that start above the
     * end of those rows stay put; the cut is pushed down past any tile that straddles it and
     * kept on an even row so small-tile clusters aren't split.
     */
    fun compute(base: List<Placement>, openIndex: Int?, childSizes: List<TileSize>, columns: Int): StartLayout {
        if (openIndex == null || openIndex !in base.indices || childSizes.isEmpty()) {
            return StartLayout(base, emptyList(), null, TileGrid.rows(base))
        }
        fun evenCeil(row: Int) = (row + 1) / 2 * 2
        var cut = evenCeil(base[openIndex].y + base[openIndex].height)
        while (true) {
            val next = evenCeil(base.filter { it.y < cut }.maxOf { it.y + it.height })
            if (next <= cut) break
            cut = next
        }
        val kids = TileGrid.pack(childSizes, columns)
        val height = TileGrid.rows(kids)
        val shifted = base.map { if (it.y >= cut) it.copy(y = it.y + height) else it }
        return StartLayout(
            tiles = shifted,
            children = kids.map { it.copy(y = it.y + cut) },
            region = Placement(0, cut, columns, height),
            rows = TileGrid.rows(shifted).coerceAtLeast(cut + height),
        )
    }
}

private val NotchWidth = 18.dp
private val NotchHeight = 9.dp

/**
 * The translucent band behind an open folder's children, with a notch pointing up at the
 * [folder] tile. Placed in the tile grid's coordinates; the band bleeds [sidePadding] past the
 * grid on both sides so it spans the screen, and takes taps so they don't count as "outside".
 */
@Composable
fun FolderRegion(region: Placement, folder: Placement, columns: Int, cell: Dp, gap: Dp, sidePadding: Dp) {
    val chrome = Metro.colors.chrome.copy(alpha = 0.92f)
    val folderCenter = cell * (folder.x + folder.width / 2f) - gap / 2 + sidePadding
    Box(
        Modifier
            .offset(-sidePadding, cell * region.y - gap)
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(cell * columns - gap + sidePadding * 2, cell * region.height + gap)
            .pointerInput(Unit) { detectTapGestures { } }
            .drawBehind {
                drawRect(chrome)
                val half = NotchWidth.toPx() / 2
                val center = folderCenter.toPx()
                val notch = Path().apply {
                    moveTo(center - half, 0f)
                    lineTo(center, -NotchHeight.toPx())
                    lineTo(center + half, 0f)
                    close()
                }
                drawPath(notch, chrome)
            },
    )
}
