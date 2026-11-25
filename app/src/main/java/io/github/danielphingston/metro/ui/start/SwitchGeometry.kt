package io.github.danielphingston.metro.ui.start

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.lerp

/*
 * The geometry of a layout switch, in pixels on start's scrolling content (so scrolling doesn't
 * move anything). Two layouts can have other columns or paddings, so a tile's cells mean other
 * places on screen in each: the switch works with the rectangles tiles are drawn in, not their
 * cells. Pure: no Compose state, nothing Android.
 */

/** A rectangle in pixels on start's scrolling content. */
@Immutable
internal data class TileRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** Something that can be drawn and divided by: finite, and not empty. */
    val isUsable: Boolean
        get() = left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() && width > 0f && height > 0f

    /** The rectangle [fraction] of the way from this one to [other]. */
    fun lerpTo(other: TileRect, fraction: Float) = TileRect(
        lerp(left, other.left, fraction),
        lerp(top, other.top, fraction),
        lerp(width, other.width, fraction),
        lerp(height, other.height, fraction),
    )

    /** This rectangle scaled by [factor] about its centre. */
    fun scaledAboutCentre(factor: Float): TileRect {
        val w = width * factor
        val h = height * factor
        return TileRect(left + (width - w) / 2f, top + (height - h) / 2f, w, h)
    }
}

/**
 * Where start's grid is on its scrolling content: the top-left of its first cell ([originX],
 * [originY]), the size of a cell including one gap ([cellPx]) and the gap ([gapPx]).
 */
@Immutable
internal data class GridFrame(val originX: Float, val originY: Float, val cellPx: Float, val gapPx: Float) {
    /** Where a tile at [placement] is drawn: its cells less one gap, as the tiles are sized. */
    fun rectOf(placement: Placement) = TileRect(
        originX + placement.x * cellPx,
        originY + placement.y * cellPx,
        placement.width * cellPx - gapPx,
        placement.height * cellPx - gapPx,
    )

    companion object {
        /**
         * The frame of a grid [gridWidthPx] wide with [leftPaddingPx] and [rightPaddingPx] around it,
         * centred in content [contentWidthPx] wide, starting [topPx] down; as start lays it out.
         */
        fun centred(contentWidthPx: Float, leftPaddingPx: Float, gridWidthPx: Float, rightPaddingPx: Float, topPx: Float, cellPx: Float, gapPx: Float) =
            GridFrame(
                originX = (contentWidthPx - (leftPaddingPx + gridWidthPx + rightPaddingPx)) / 2f + leftPaddingPx,
                originY = topPx,
                cellPx = cellPx,
                gapPx = gapPx,
            )
    }
}

/**
 * The layer transform (from the top-left corner) that draws a tile laid out at one rectangle as
 * if it were at another.
 */
@Immutable
internal data class LayerTransform(val translationX: Float, val translationY: Float, val scaleX: Float, val scaleY: Float) {
    companion object {
        val NONE = LayerTransform(0f, 0f, 1f, 1f)

        /** Draws what is laid out at [laidOut] at [shown]; [NONE] unless both can be drawn (no NaN or division by zero). */
        fun between(laidOut: TileRect, shown: TileRect): LayerTransform {
            if (!laidOut.isUsable || !shown.isUsable) return NONE
            return LayerTransform(
                translationX = shown.left - laidOut.left,
                translationY = shown.top - laidOut.top,
                scaleX = shown.width / laidOut.width,
                scaleY = shown.height / laidOut.height,
            )
        }
    }
}

/**
 * What a switch does to each tile, worked out once when it happens: tiles on screen before and
 * after move from where they were drawn ([from]), the ones that left fade out where they were
 * ([leaving]) and new ones fade in ([entering]). Keys are composition keys (see [TileKeys]).
 */
@Immutable
internal class SwitchPlan<T>(
    val from: Map<Long, TileRect>,
    val leaving: List<Departure<T>>,
    val entering: Set<Long>,
) {
    /** [leaving] by key. */
    val leavingByKey: Map<Long, Departure<T>> = leaving.associateBy { it.key }

    /** A tile that left: drawn at [placement] of the new grid, shown at [rect] (where it was) until it has gone. */
    class Departure<T>(val key: Long, val item: T, val placement: Placement, val rect: TileRect)

    /**
     * How the tile with [key] laid out at [laidOut] is drawn [progress] (0 to 1) into the switch:
     * where it is shown and how opaque.
     */
    fun frameOf(key: Long, laidOut: TileRect, progress: Float, leavingRect: TileRect? = null): Pair<TileRect, Float> {
        val p = progress.coerceIn(0f, 1f)
        return when {
            // Leaving tiles are gone early and new ones come in late, so the two barely overlap.
            leavingRect != null -> phase(p, 0f, LEAVING_END).let { q -> leavingRect.scaledAboutCentre(lerp(1f, LEAVING_SCALE, q)) to 1f - q }
            key in entering -> phase(p, ENTERING_START, 1f).let { q -> laidOut.scaledAboutCentre(lerp(ENTERING_SCALE, 1f, q)) to q }
            else -> (from[key]?.lerpTo(laidOut, p) ?: laidOut) to 1f
        }
    }

    /** How far (0 to 1) [p] is through the part of the switch from [start] to [end]. */
    private fun phase(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)

    companion object {
        /** How small a leaving tile gets as it fades, and by when (as a share of the switch) it is gone. */
        const val LEAVING_SCALE = 0.8f
        const val LEAVING_END = 0.45f

        /** How small a new tile starts, and when (as a share of the switch) it starts to appear. */
        const val ENTERING_SCALE = 0.92f
        const val ENTERING_START = 0.35f

        private val EMPTY = SwitchPlan<Nothing>(emptyMap(), emptyList(), emptySet())

        @Suppress("UNCHECKED_CAST")
        fun <T> none(): SwitchPlan<T> = EMPTY as SwitchPlan<T>

        /**
         * The plan for a switch from [before] (laid out in [beforeFrame]) to [now]: keys of both move,
         * keys only in [before] leave, keys only in [now] enter. [drawnAt] gives where a tile laid out
         * at a rectangle is drawn right now, for a switch that starts while another is still moving.
         * [scrolledPx] is how far start was scrolled: the switch scrolls it to the top at once, so the
         * old rectangles move up by as much and tiles stay where they were on screen.
         */
        fun <T> of(
            before: Map<Long, Pair<T, Placement>>,
            beforeFrame: GridFrame,
            now: Set<Long>,
            scrolledPx: Float = 0f,
            drawnAt: (key: Long, laidOut: TileRect) -> TileRect = { _, laidOut -> laidOut },
        ): SwitchPlan<T> {
            val from = HashMap<Long, TileRect>()
            val leaving = ArrayList<Departure<T>>()
            before.forEach { (key, value) ->
                val rect = drawnAt(key, beforeFrame.rectOf(value.second)).let { it.copy(top = it.top - scrolledPx) }
                if (key in now) from[key] = rect else leaving += Departure(key, value.first, value.second, rect)
            }
            return SwitchPlan(from, leaving, now - before.keys)
        }
    }
}
