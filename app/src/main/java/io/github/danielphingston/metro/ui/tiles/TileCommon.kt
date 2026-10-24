package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro

/** Tile typography and spacing scaled to the grid cell; see [TileMetrics]. */
@Composable
internal fun rememberTileMetrics(cellSize: Dp): TileMetrics = TileMetrics(cellSize, LocalDensity.current)

/** A plain accent tile with its label: the fallback face for any tile kind. */
@Composable
fun PlaceholderTile(tile: ResolvedTile, cellSize: Dp) {
    val m = rememberTileMetrics(cellSize)
    Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
        TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
    }
}
