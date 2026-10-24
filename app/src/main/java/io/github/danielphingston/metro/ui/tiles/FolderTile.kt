package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.tileSurface

/** Columns and rows of mini icons a folder of [size] previews: nine on a medium tile, more on bigger ones. */
internal fun folderPreviewGrid(size: TileSize): Pair<Int, Int> = when (size) {
    TileSize.SMALL -> 2 to 2
    TileSize.MEDIUM -> 3 to 3
    TileSize.WIDE -> 7 to 3
    TileSize.LARGE -> 7 to 6
}

/**
 * A closed folder: a grid of its children's icons over the tile surface, the folder's name at
 * the bottom left and a chevron at the bottom right. While [open] the chevron points up and the
 * tile is lightened, so it reads as the one the children below belong to.
 */
@Composable
fun FolderTile(tile: ResolvedTile, cellSize: Dp, icons: IconCache, monochrome: Boolean, open: Boolean) {
    val m = rememberTileMetrics(cellSize)
    val tileSize = tile.spec.size
    val small = tileSize == TileSize.SMALL
    val (columns, rows) = folderPreviewGrid(tileSize)
    val slot = m.dp(if (small) 36f else 48f)
    val iconSize = m.dp(if (small) 22f else 32f)
    Box(Modifier.fillMaxSize().tileSurface()) {
        if (open) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.16f)))
        Column(
            Modifier.align(if (small) Alignment.Center else Alignment.TopCenter).padding(top = if (small) m.dp(0f) else m.dp(8f)),
        ) {
            tile.children.take(columns * rows).chunked(columns).forEach { row ->
                Row(Modifier.width(slot * columns)) {
                    row.forEach { child ->
                        Box(Modifier.size(slot).padding(m.dp(2f)).background(Color.White.copy(alpha = 0.16f)), Alignment.Center) {
                            child.app?.let { AppIcon(it, icons, iconSize, monochrome) }
                        }
                    }
                }
            }
        }
        if (small) return@Box
        TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart).padding(end = m.dp(22f)))
        val chevron = Metro.colors.onAccent
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = m.dp(9f), bottom = m.dp(10f))
                .size(m.dp(12f), m.dp(7f))
                .drawBehind {
                    val stroke = 1.5.dp.toPx()
                    val (top, bottom) = if (open) size.height to 0f else 0f to size.height
                    drawLine(chevron, Offset(0f, top), Offset(size.width / 2, bottom), stroke, StrokeCap.Round)
                    drawLine(chevron, Offset(size.width / 2, bottom), Offset(size.width, top), stroke, StrokeCap.Round)
                },
        )
    }
}
