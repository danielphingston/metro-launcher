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
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileLook
import io.github.danielphingston.metro.data.TileLooks
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
    val look = TileLooks.shown(tile.spec)
    val face = ActionFaces.of(tile.spec)
    val plain = face == ActionFace.PLAIN
    val contact = action.icon as? ActionIcon.Contact
    if (contact != null && face.contactPhoto) {
        val photo = rememberContactPhoto(contact, cellSize * maxOf(tile.spec.width, tile.spec.height))
        if (photo != null) return PhotoTile(tile, cellSize, photo, showName = face == ActionFace.CONTACT_PHOTO)
    }
    val glyph = when (val icon = action.icon) {
        is ActionIcon.Glyph -> SearchGlyphs.byId(icon.id)
        // Without a photo (none set, or no contacts permission) a contact is the person drawing.
        is ActionIcon.Contact -> SearchGlyphs.person
        else -> null
    }
    val custom0 = tile.spec.icon
    val app = tile.app?.takeIf { action.icon is ActionIcon.App }
    if (glyph == null && app == null && custom0 == null && plain) return PlaceholderTile(tile, cellSize)
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
        if (face == ActionFace.STYLED) {
            StyledTileFace(tile.label, size, 0, m, look, tile.spec.image, appIcon = picture)
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

/** How a search tile draws itself; see [ActionFaces.of]. */
enum class ActionFace(val contactPhoto: Boolean = false) {
    /** A contact's photo filling the tile, with the name strip. */
    CONTACT_PHOTO(contactPhoto = true),

    /** A contact's photo filling the tile without the name ("show name" off on the photo face). */
    CONTACT_PHOTO_NO_NAME(contactPhoto = true),

    /** The picture over the name label (a contact without a photo is the person drawing). */
    PLAIN,

    /** Any other look, drawn by [StyledTileFace] (which shows the label only when the look does). */
    STYLED,
}

object ActionFaces {
    /**
     * The face of search tile [spec]. As before the customise sheet, only the plain style without a
     * picked icon is the plain face (a contact's photo when it has one); every older style, and a
     * hidden name, is the styled face. A contact's photo face with "show name" off is stored as the
     * plain style plus the hidden name, which older styles never were, and keeps the photo.
     */
    fun of(spec: TileSpec): ActionFace {
        val action = spec.content as? TileContent.Action ?: return ActionFace.STYLED
        val look = TileLooks.shown(spec)
        val plainStyle = spec.style == TileStyle.ICON && spec.icon == null
        val contact = action.icon is ActionIcon.Contact
        return when {
            plainStyle && contact && look == TileLook() -> ActionFace.CONTACT_PHOTO
            plainStyle && contact && look == TileLook(showName = false) -> ActionFace.CONTACT_PHOTO_NO_NAME
            plainStyle && look == TileLook() -> ActionFace.PLAIN
            else -> ActionFace.STYLED
        }
    }
}

@Composable
private fun PhotoTile(tile: ResolvedTile, cellSize: Dp, photo: ImageBitmap, showName: Boolean = true) {
    val m = rememberTileMetrics(cellSize)
    Box(Modifier.fillMaxSize().tileSurface()) {
        Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (tile.spec.size != TileSize.SMALL && showName) {
            Box(Modifier.fillMaxSize().background(PhotoScrim))
            TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
        }
    }
}
