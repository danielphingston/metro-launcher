package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileStyle
import io.github.danielphingston.metro.live.PeopleSource
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.CustomTileIcon
import io.github.danielphingston.metro.ui.start.GlyphPicture
import io.github.danielphingston.metro.ui.start.StyledTileFace
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.tileSurface

/** Darkens the foot of a contact's photo so the white label stays readable on a light picture. */
private val PhotoScrim = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))

/**
 * A tile pinned from the search results: its picture (the app's icon, or the row's glyph in white)
 * over its label, like a shortcut tile; a contact fills the tile with their photo. With no picture
 * it is the plain labelled tile.
 */
@Composable
fun ActionTile(tile: ResolvedTile, cellSize: Dp, icons: IconCache, monochrome: Boolean) {
    val action = tile.spec.content as? TileContent.Action ?: return PlaceholderTile(tile, cellSize)
    val style = tile.spec.style
    val contact = action.icon as? ActionIcon.Contact
    // A contact's photo is its default face; any other style replaces it.
    if (contact != null && style == TileStyle.ICON && tile.spec.icon == null) {
        val photo = rememberContactPhoto(contact, cellSize * maxOf(tile.spec.width, tile.spec.height))
        if (photo != null) return PhotoTile(tile, cellSize, photo)
    }
    val glyph = when (val icon = action.icon) {
        is ActionIcon.Glyph -> SearchGlyphs.byId(icon.id)
        // Without a photo (none set, or no contacts permission) a contact is the person drawing.
        is ActionIcon.Contact -> SearchGlyphs.person
        else -> null
    }
    val custom0 = tile.spec.icon
    val app = tile.app?.takeIf { action.icon is ActionIcon.App }
    if (glyph == null && app == null && custom0 == null && style == TileStyle.ICON) return PlaceholderTile(tile, cellSize)
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    val white = Metro.colors.onAccent
    val custom = tile.spec.icon
    val picture: @Composable (Dp, Modifier) -> Unit = { iconSize, modifier ->
        if (custom != null) {
            CustomTileIcon(custom, iconSize, icons, modifier)
        } else if (app != null) {
            AppIcon(app, icons, iconSize, monochrome, modifier)
        } else if (glyph != null) {
            GlyphPicture(glyph, iconSize, white, modifier)
        }
    }
    Box(Modifier.fillMaxSize().tileSurface()) {
        if (style != TileStyle.ICON || custom != null) {
            StyledTileFace(tile.label, size, 0, m, style, tile.spec.image, appIcon = picture)
            return@Box
        }
        val iconSize = when (size) {
            TileSize.SMALL -> m.dp(40f)
            TileSize.LARGE -> m.dp(80f)
            else -> m.dp(64f)
        }
        picture(iconSize, Modifier.align(Alignment.Center))
        if (size != TileSize.SMALL) TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
    }
}

/** The contact's photo for a tile [side] across, or null while loading and when there is none. */
@Composable
private fun rememberContactPhoto(contact: ActionIcon.Contact, side: Dp): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { side.roundToPx() }
    return produceState(PeopleSource.lastContactPhoto(contact.uri, px)?.asImageBitmap(), contact, px) {
        value = PeopleSource.contactPhoto(context, contact.uri, px)?.asImageBitmap()
    }.value
}

@Composable
private fun PhotoTile(tile: ResolvedTile, cellSize: Dp, photo: ImageBitmap) {
    val m = rememberTileMetrics(cellSize)
    Box(Modifier.fillMaxSize().tileSurface()) {
        Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (tile.spec.size != TileSize.SMALL) {
            Box(Modifier.fillMaxSize().background(PhotoScrim))
            TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
        }
    }
}
