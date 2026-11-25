package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.danielphingston.metro.ui.ResolvedTile

/** How long a layout switch's moves and fades take, before the animation speed setting. */
internal const val SWITCH_MILLIS = 260

/** How long a switch that changes the grid waits for the new layout's settings before it moves anyway. */
internal const val SWITCH_SETTINGS_WAIT_MILLIS = 250L

/**
 * A layout switch on start, drawn: tiles both layouts have move and resize from where they were
 * drawn to where the new grid puts them (other columns or paddings included), tiles that left fade
 * and shrink where they were, and new ones fade in. The geometry is [SwitchPlan]'s; this holds the
 * switch in progress. Tiles are laid out at their new places at once ([active]: their own placement
 * animation is skipped) and only drawn elsewhere, through a layer, so nothing is measured or
 * recomposed per frame. Start jumps to its top as the switch begins, and the plan allows for how far
 * it was scrolled, so tiles start exactly where they were on screen. Main thread.
 */
@Stable
internal class SwitchMotion {
    /** A tile to draw on start under composition key [key]; [leaving] ones are on their way out. */
    class Shown(val key: Long, val tile: ResolvedTile, val placement: Placement, val leaving: Boolean)

    private var seenGeneration = -1
    private var shown: Map<Long, Pair<ResolvedTile, Placement>> = emptyMap()
    private var shownFrame: GridFrame? = null
    private var plan: SwitchPlan<ResolvedTile> = SwitchPlan.none()
    private var pending = false

    /** Where start was scrolled when the switch began, and how to read it now (only while drawing). */
    private var scrollAtSwitch = 0
    private var scroll: () -> Int = { 0 }

    /** How far start has scrolled since the switch began: 0 until it jumps to the top, then minus the old scroll. */
    private fun scrolledSince(): Float = (scroll() - scrollAtSwitch).toFloat()

    /** 0 when a switch has just happened, 1 once it is done. Only read while drawing. */
    private var progress by mutableFloatStateOf(1f)

    /** Recomposes start when a switch is over and the leaving tiles can go. */
    var ended by mutableIntStateOf(0)
        private set

    /** Whether a switch is being drawn: tiles then snap to their places, the layer does the moving. */
    var active by mutableStateOf(false)
        private set

    /** Whether the switch being drawn may change the grid, so its settings are waited for; see [TileKeys.gridChanges]. */
    var waitsForGrid = false
        private set

    /**
     * Called while composing start with the tiles it shows by key, laid out in [frame]: on a new
     * [generation] plans the switch from what was shown before. Returns whether a switch just happened.
     */
    fun update(
        generation: Int,
        now: Map<Long, Pair<ResolvedTile, Placement>>,
        frame: GridFrame,
        gridChanges: Boolean,
        scrolledPx: () -> Int,
        animate: () -> Boolean,
    ): Boolean {
        val switched = seenGeneration >= 0 && generation != seenGeneration
        seenGeneration = generation
        val before = shown
        val beforeFrame = shownFrame
        shown = now
        shownFrame = frame
        if (!switched) return false
        pending = true
        // Animations off (the system's animator scale), or nothing drawn yet: the new layout just appears.
        if (!animate() || beforeFrame == null) {
            clear()
            return true
        }
        // A switch during a switch starts each tile from where it is drawn now: read before it is
        // reset, and without observing, so composition never follows the per-frame progress.
        val previous = plan
        val at = Snapshot.withoutReadObservation { progress }
        // Where start is scrolled now; the layer follows scrolling from here (see modifier). Read
        // without observing too: composition must not follow scrolling.
        val previousScrolledSince = Snapshot.withoutReadObservation { scrolledSince() }
        scrollAtSwitch = Snapshot.withoutReadObservation(scrolledPx)
        scroll = scrolledPx
        plan = SwitchPlan.of(before, beforeFrame, now.keys) { key, laidOut -> previous.frameOf(key, laidOut, at, scrolledSince = previousScrolledSince).first }
        // Set here, so the frame that first shows the new layout already draws its start.
        progress = 0f
        active = true
        waitsForGrid = gridChanges
        return true
    }

    /** [tiles] at [placements] under their keys, then the tiles still leaving. */
    fun onScreen(tiles: List<ResolvedTile>, placements: List<Placement>, keyOf: (Long) -> Long): List<Shown> =
        tiles.indices.map { Shown(keyOf(tiles[it].spec.id), tiles[it], placements[it], leaving = false) } +
            plan.leaving.map { Shown(it.key, it.item, it.placement, leaving = true) }

    /** Whether a switch happened that the screen hasn't reacted to yet; true once per switch. */
    fun takeSwitch(): Boolean = pending.also { pending = false }

    /**
     * Plays the planned switch to its end, after [beforeMoving] (a bounded wait for the new grid's
     * settings). However it ends (done, cancelled, failed) the switch is cleared and [ended] bumped,
     * so nothing is left drawn or snapping; a play cancelled by a newer switch leaves that one's plan
     * alone. Without a plan (animations off, see [update]) it only clears.
     */
    suspend fun play(durationMillis: Int, beforeMoving: suspend () -> Unit = {}) {
        val playing = plan
        try {
            if (!active) return
            beforeMoving()
            animate(progress, 1f, animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) { value, _ ->
                if (plan === playing) progress = value
            }
        } finally {
            if (plan === playing) clear()
            ended++
        }
    }

    private fun clear() {
        scrollAtSwitch = 0
        scroll = { 0 }
        progress = 1f
        active = false
        waitsForGrid = false
        plan = SwitchPlan.none()
    }

    /**
     * The draw-only part for the tile with [key] laid out at [placement] in [frame] (the frame of
     * this composition, so it follows the new layout's settings when they land): it is drawn where
     * the plan puts it. Nothing for a tile the switch doesn't touch.
     */
    fun modifier(key: Long, placement: Placement, frame: GridFrame, isLeaving: Boolean = false): Modifier {
        val plan = plan
        val leavingRect = if (isLeaving) plan.leavingByKey[key]?.rect else null
        if (leavingRect == null && key !in plan.entering && key !in plan.from) return Modifier
        val laidOut = frame.rectOf(placement)
        return Modifier.graphicsLayer {
            // Reading the scroll here, while drawing, keeps tiles in place whichever frame the jump to the top lands on.
            val (rect, opacity) = plan.frameOf(key, laidOut, progress, leavingRect, scrolledSince())
            val transform = LayerTransform.between(laidOut, rect)
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = transform.translationX
            translationY = transform.translationY
            scaleX = transform.scaleX
            scaleY = transform.scaleY
            alpha = opacity
        }
    }
}
