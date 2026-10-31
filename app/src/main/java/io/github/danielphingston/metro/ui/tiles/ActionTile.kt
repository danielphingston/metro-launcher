package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.tileSurface

/** The search glyphs are drawn for this box; a tile scales the box up so the strokes keep their proportions. */
private val GlyphBox = 18.dp

/**
 * A tile pinned from the search results: its picture (the app's icon, or the row's glyph in white)
 * over its label, like a shortcut tile. With neither picture it is the plain labelled tile.
 */
@Composable
fun ActionTile(tile: ResolvedTile, cellSize: Dp, icons: IconCache, monochrome: Boolean) {
    val action = tile.spec.content as? TileContent.Action ?: return PlaceholderTile(tile, cellSize)
    val glyph = (action.icon as? ActionIcon.Glyph)?.let { SearchGlyphs.byId(it.id) }
    val app = tile.app?.takeIf { action.icon is ActionIcon.App }
    if (glyph == null && app == null) return PlaceholderTile(tile, cellSize)
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    val iconSize = when (size) {
        TileSize.SMALL -> m.dp(40f)
        TileSize.LARGE -> m.dp(80f)
        else -> m.dp(64f)
    }
    Box(Modifier.fillMaxSize().tileSurface()) {
        val center = Modifier.align(Alignment.Center)
        if (app != null) {
            AppIcon(app, icons, iconSize, monochrome, center)
        } else if (glyph != null) {
            val white = Metro.colors.onAccent
            // The glyphs' hairline is thin for a tile: draw at 18dp and scale, so it thickens with the picture.
            val scale = iconSize / GlyphBox * 0.7f
            Box(
                center
                    .size(GlyphBox)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .drawBehind { glyph.draw(this, white) },
            )
        }
        if (size != TileSize.SMALL) TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
    }
}
