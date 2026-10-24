package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Frees what the system holds for [specs] once their tiles are gone: widget ids and pinned
 * shortcuts, including those of folder children. Shortcut calls are binder work and run off
 * the calling thread.
 */
suspend fun releaseTileResources(context: Context, specs: List<TileSpec>) {
    val all = specs.flatMap { spec -> listOf(spec) + ((spec.content as? TileContent.Folder)?.children.orEmpty()) }
    all.forEach { (it.content as? TileContent.Widget)?.let { widget -> WidgetTiles.release(context, widget.appWidgetId) } }
    val shortcuts = all.mapNotNull { it.content as? TileContent.Shortcut }
    if (shortcuts.isNotEmpty()) {
        withContext(Dispatchers.Default) { shortcuts.forEach { ShortcutTiles.release(context, it) } }
    }
}

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
