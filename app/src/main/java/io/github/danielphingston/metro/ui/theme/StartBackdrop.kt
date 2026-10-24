package io.github.danielphingston.metro.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
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

/**
 * The start screen's background: the plain page colour, the full-screen photo under a scrim, or
 * nothing at all so the system wallpaper shows through the transparent window.
 */
fun Modifier.startBackdrop(backdrop: StartBackdrop, page: Color): Modifier = drawBehind {
    when (backdrop.mode) {
        StartBackground.SOLID, StartBackground.PHOTO_TILES -> drawRect(page)
        StartBackground.PHOTO_FULL -> {
            drawRect(page)
            backdrop.image?.let { drawPhoto(it, size.width, size.height, backdrop.offsetFor(size.height)) }
            drawRect(page.copy(alpha = FULL_PHOTO_SCRIM))
        }
        StartBackground.SYSTEM_WALLPAPER -> Unit
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
            clipRect {
                translate(-origin.x, -origin.y) { drawPhoto(image, root.width.toFloat(), root.height.toFloat(), offset) }
            }
        }
        drawRect(color, alpha = backdrop.tileAlpha)
        drawContent()
    }
}
