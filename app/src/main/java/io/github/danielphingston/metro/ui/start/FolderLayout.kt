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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.theme.Metro

/**
 * Where everything sits on the start screen. [tiles] are the top-level tiles in list order. With a
 * folder open, [children] are its children and [region] the cells they fill, right under the
 * folder; [tiles] are moved down out of its way (see [FolderLayout.compute]).
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
     * [openIndex] is an open folder, opens a full-width region for its [childSizes] directly under
     * the folder, wherever it sits; the children are packed in list order. With `cut` the row
     * under the folder and `height` the region's rows:
     * - A tile that ends at or above `cut` stays where it is.
     * - A tile that starts at or below `cut` moves down by `height`.
     * - A tile beside the folder that straddles `cut` moves down to start just below the region.
     * - Moved tiles that now overlap are then separated top to bottom, then left to right: the
     *   later one is pushed down to just below the earlier one, repeatedly.
     * Tiles never change column, nothing rises into the gaps they leave, and the layout is
     * derived from [base] each time, so closing the folder gives the stored layout back.
     */
    fun compute(base: List<Placement>, openIndex: Int?, childSizes: List<TileSize>, columns: Int): StartLayout {
        if (openIndex == null || openIndex !in base.indices || childSizes.isEmpty()) {
            return StartLayout(base, emptyList(), null, TileGrid.rows(base))
        }
        val cut = base[openIndex].y + base[openIndex].height
        val kids = TileGrid.pack(childSizes, columns)
        val height = TileGrid.rows(kids)
        val shifted = base.toMutableList()
        val moved = base.indices.filter { base[it].y + base[it].height > cut }
        for (i in moved) shifted[i] = base[i].let { it.copy(y = if (it.y >= cut) it.y + height else cut + height) }
        val settled = ArrayList<Placement>()
        for (i in moved.sortedWith(compareBy({ shifted[it].y }, { shifted[it].x }, { it }))) {
            while (true) {
                val hit = settled.firstOrNull { shifted[i].overlaps(it) } ?: break
                shifted[i] = shifted[i].copy(y = hit.y + hit.height)
            }
            settled += shifted[i]
        }
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
 * [folder] tile. Placed in the tile grid's coordinates; the band bleeds [leftPadding] and [rightPadding] past
 * the grid so it spans the screen, and takes taps so they don't count as "outside".
 */
@Composable
fun FolderRegion(region: Placement, folder: Placement, columns: Int, cell: Dp, gap: Dp, leftPadding: Dp, rightPadding: Dp) {
    val chrome = Metro.colors.chrome.copy(alpha = 0.92f)
    val folderCenter = cell * (folder.x + folder.width / 2f) - gap / 2 + leftPadding
    val turnstile = LocalTurnstile.current
    Box(
        Modifier
            // The tiles in the folder swing away on a launch; a panel left standing behind them
            // would show through the gaps, so it goes for the whole run and returns with the tiles.
            .graphicsLayer { alpha = if (turnstile.busy) 0f else 1f }
            .offset(-leftPadding, cell * region.y - gap)
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(cell * columns - gap + leftPadding + rightPadding, cell * region.height + gap)
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
