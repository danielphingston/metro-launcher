package io.github.danielphingston.metro.ui.start

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.IconColor
import io.github.danielphingston.metro.data.TileColors
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.theme.LocalMetroColors
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.LocalTileDecor
import io.github.danielphingston.metro.ui.theme.TileDecor
import io.github.danielphingston.metro.ui.theme.LocalTileColourSettings
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.readableOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app whose icon gives [tile] its automatic colour: a folder takes its first child's; null when there is none. */
internal fun tileIconApp(tile: ResolvedTile): AppInfo? = when (tile.spec.content) {
    is TileContent.App, is TileContent.Shortcut, is TileContent.Action, TileContent.Assistant -> tile.app
    is TileContent.Folder -> tile.children.firstNotNullOfOrNull(::tileIconApp)
    else -> null
}

/** Whether "from icon" can mean something for [spec], so the tile colour menu offers it. */
internal fun offersIconColour(spec: TileSpec): Boolean = when (val c = spec.content) {
    is TileContent.App, is TileContent.Shortcut, TileContent.Assistant -> true
    is TileContent.Action -> c.icon is io.github.danielphingston.metro.data.ActionIcon.App
    is TileContent.Folder -> c.children.any(::offersIconColour)
    else -> false
}

/**
 * Dark ink for a tile of [colour], or null to keep white. Tile text is white because tiles are
 * usually translucent over a dark page; only a fully opaque tile can safely switch to black on a
 * light colour such as yellow.
 */
internal fun tileInk(colour: Color, tileAlpha: Float): Color? =
    if (tileAlpha >= 0.99f && readableOn(colour) == Color.Black) Color.Black else null

/**
 * Draws [content] with the tile's own colour in place of the accent, so the surface, glyphs and
 * badges of every tile kind pick it up without knowing about it. Tiles on the accent (the default)
 * pass through unchanged. The icon colour is read off the main thread, once per app (see
 * [IconCache.dominantColor]); until it is ready the tile shows the accent.
 */
@Composable
internal fun TileColourScope(tile: ResolvedTile, icons: IconCache, content: @Composable () -> Unit) {
    val settings = LocalTileColourSettings.current
    val iconApp = tileIconApp(tile)
    val usesIcon = iconApp != null && TileColors.usesIcon(tile.spec.color, settings.mode)
    val raw by produceState<Int?>(null, iconApp, usesIcon, icons.generation) {
        value = if (usesIcon && iconApp != null) withContext(Dispatchers.Default) { icons.dominantColor(iconApp) } else null
    }
    val colour = TileColors.resolve(
        tile.spec.color, settings.mode,
        raw?.let { IconColor.adjust(it, settings.vividness) }.takeIf { usesIcon },
    )
    val base = Metro.colors
    val spec = tile.spec
    val decor = remember(spec.hideName, spec.showsBackground, spec.transparency) { tileDecor(spec) }
    // The tile's own alpha (its transparency, or none without a background) decides the ink.
    val tileAlpha = LocalStartBackdrop.current.surfaceAlpha(decor)
    val colors = remember(base, colour, tileAlpha) {
        colour?.let { c -> base.copy(accent = Color(c), tileInk = tileInk(Color(c), tileAlpha)) } ?: base
    }
    CompositionLocalProvider(LocalMetroColors provides colors, LocalTileDecor provides decor, content = content)
}

/** The surface and label settings of [spec] that every part of the tile follows; see [TileDecor]. */
internal fun tileDecor(spec: TileSpec): TileDecor {
    val decor = TileDecor(showName = !spec.hideName, background = spec.showsBackground, transparency = spec.transparency)
    return if (decor == TileDecor.Default) TileDecor.Default else decor
}
