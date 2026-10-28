package io.github.danielphingston.metro.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.StartBackground
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What is behind the tiles on the start screen, shared with every tile through
 * [LocalStartBackdrop]. The image is [BackgroundPhoto.HEADROOM] taller than the screen and
 * fixed to it; [parallax] says how far it has moved up with the start screen's scroll.
 *
 * Only the draw phase reads [parallax], so scrolling never recomposes anything.
 */
@Stable
class StartBackdrop(
    val mode: StartBackground,
    val image: ImageBitmap?,
    /** 0..1: how much of the background shows through the tiles' accent layer. */
    val transparency: Float,
    /** The image's upward offset in px, before it is limited to the headroom. */
    val parallax: () -> Float,
) {
    /** Alpha of the accent layer on a tile. In SOLID mode transparency only blends lightly. */
    val tileAlpha: Float
        get() = 1f - transparency * if (mode == StartBackground.SOLID) SOLID_BLEND else 1f

    /** The parallax offset for a screen [rootHeight] px tall. */
    fun offsetFor(rootHeight: Float): Float = min(parallax(), rootHeight * BackgroundPhoto.HEADROOM).coerceAtLeast(0f)

    companion object {
        /** In SOLID mode even full transparency keeps 40% of the accent, so white text stays readable. */
        private const val SOLID_BLEND = 0.6f

        val Solid = StartBackdrop(StartBackground.SOLID, null, 0f) { 0f }

        /** The backdrop for [settings]; photo modes look like SOLID until [image] has loaded. */
        fun of(settings: LauncherSettings, image: ImageBitmap?, parallax: () -> Float): StartBackdrop =
            if (settings.showsPhoto && image == null) {
                StartBackdrop(StartBackground.SOLID, null, 0f, parallax)
            } else {
                StartBackdrop(settings.background, image, settings.transparencyFraction, parallax)
            }
    }
}

val LocalStartBackdrop = staticCompositionLocalOf { StartBackdrop.Solid }

/**
 * Draws the photo as it sits behind a screen of [rootW] x [rootH] px, moved up by [offset].
 * The caller positions the scope so that its origin is the top-left of the screen.
 */
private fun DrawScope.drawPhoto(image: ImageBitmap, rootW: Float, rootH: Float, offset: Float) {
    val full = IntSize(rootW.roundToInt(), (rootH * (1f + BackgroundPhoto.HEADROOM)).roundToInt())
    drawImage(image, dstOffset = IntOffset(0, -offset.roundToInt()), dstSize = full)
}

/** The part of the photo to draw for one tile: a source rectangle in the bitmap and where it lands, in tile pixels. */
internal class PhotoSlice(val srcOffset: IntOffset, val srcSize: IntSize, val dstOffset: IntOffset, val dstSize: IntSize)

/**
 * Which part of [drawPhoto]'s picture lies behind the tile at ([tileX], [tileY]) in the root,
 * [tileW] x [tileH] px. The source rectangle is widened to whole bitmap pixels and the destination
 * follows from it, so the result is within half a pixel of drawing the whole picture and clipping.
 * Null when the tile is outside the picture.
 */
internal fun photoSlice(
    imageW: Int, imageH: Int,
    rootW: Float, rootH: Float,
    offset: Float,
    tileX: Float, tileY: Float, tileW: Float, tileH: Float,
): PhotoSlice? {
    val fullW = rootW.roundToInt()
    val fullH = (rootH * (1f + BackgroundPhoto.HEADROOM)).roundToInt()
    if (imageW <= 0 || imageH <= 0 || fullW <= 0 || fullH <= 0) return null
    val kx = fullW.toFloat() / imageW
    val ky = fullH.toFloat() / imageH
    // The tile in the photo's own destination space, whose origin is at (0, -offset).
    val top = tileY + offset.roundToInt()
    val sx0 = floor(tileX / kx).toInt().coerceIn(0, imageW)
    val sx1 = ceil((tileX + tileW) / kx).toInt().coerceIn(0, imageW)
    val sy0 = floor(top / ky).toInt().coerceIn(0, imageH)
    val sy1 = ceil((top + tileH) / ky).toInt().coerceIn(0, imageH)
    if (sx1 <= sx0 || sy1 <= sy0) return null
    val dx0 = (sx0 * kx - tileX).roundToInt()
    val dx1 = (sx1 * kx - tileX).roundToInt()
    val dy0 = (sy0 * ky - top).roundToInt()
    val dy1 = (sy1 * ky - top).roundToInt()
    return PhotoSlice(
        IntOffset(sx0, sy0), IntSize(sx1 - sx0, sy1 - sy0),
        IntOffset(dx0, dy0), IntSize((dx1 - dx0).coerceAtLeast(1), (dy1 - dy0).coerceAtLeast(1)),
    )
}

/**
 * The start screen's background: the plain page colour, the full-screen photo under a scrim, or
 * cleared to transparent so the system wallpaper shows through the transparent window.
 */
fun Modifier.startBackdrop(backdrop: StartBackdrop, page: Color): Modifier = drawBehind {
    when (backdrop.mode) {
        StartBackground.SOLID, StartBackground.PHOTO_TILES -> drawRect(page)
        StartBackground.PHOTO_FULL -> {
            drawRect(page)
            backdrop.image?.let { drawPhoto(it, size.width, size.height, backdrop.offsetFor(size.height)) }
            drawRect(page.copy(alpha = FULL_PHOTO_SCRIM))
        }
        // The window starts opaque and only turns transparent at run time, and the renderer can
        // keep drawing it as opaque, which skips clearing between frames. Clear explicitly, or
        // each frame is drawn over the last and moving content leaves trails.
        StartBackground.SYSTEM_WALLPAPER -> drawRect(Color.Transparent, blendMode = BlendMode.Clear)
    }
}

private const val FULL_PHOTO_SCRIM = 0.4f

/**
 * The surface of a tile: the accent colour at the configured transparency, over the part of
 * the photo behind the tile in PHOTO_TILES mode. Every tile face draws its background through
 * this, so the backdrop modes need no other tile code.
 *
 * Nothing is recomposed while scrolling: the tile's position is only read when drawing.
 */
fun Modifier.tileSurface(): Modifier = composed {
    val backdrop = LocalStartBackdrop.current
    val accent = Metro.colors.accent
    this.then(TileSurfaceElement(backdrop, accent))
}

private data class TileSurfaceElement(val backdrop: StartBackdrop, val color: Color) :
    ModifierNodeElement<TileSurfaceNode>() {
    override fun create() = TileSurfaceNode(backdrop, color)

    override fun update(node: TileSurfaceNode) {
        node.backdrop = backdrop
        node.color = color
        node.invalidateDraw()
    }
}

private class TileSurfaceNode(var backdrop: StartBackdrop, var color: Color) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        // Only the photo depends on where the tile is.
        if (backdrop.mode == StartBackground.PHOTO_TILES) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val image = backdrop.image
        val coordinates = coordinates
        if (backdrop.mode == StartBackground.PHOTO_TILES && image != null && coordinates?.isAttached == true) {
            val root = coordinates.findRootCoordinates().size
            val origin: Offset = coordinates.positionInRoot()
            val offset = backdrop.offsetFor(root.height.toFloat())
            // Only the slice of the photo behind this tile, so the cost follows the tile's area
            // and not the screen's.
            val slice = photoSlice(
                imageW = image.width, imageH = image.height,
                rootW = root.width.toFloat(), rootH = root.height.toFloat(),
                offset = offset, tileX = origin.x, tileY = origin.y,
                tileW = size.width, tileH = size.height,
            )
            if (slice != null) {
                clipRect {
                    drawImage(
                        image,
                        srcOffset = slice.srcOffset, srcSize = slice.srcSize,
                        dstOffset = slice.dstOffset, dstSize = slice.dstSize,
                    )
                }
            }
        }
        drawRect(color, alpha = backdrop.tileAlpha)
        drawContent()
    }
}
