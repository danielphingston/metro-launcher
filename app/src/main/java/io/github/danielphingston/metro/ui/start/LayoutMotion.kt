package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.LocalMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
     * Where the switch draws the tile with [key] laid out at [placement] in [frame] right now, and
     * how opaque: what [modifier] draws every frame. It reads the progress and the scroll, so call
     * it while drawing; tests call it to see what a frame would show.
     */
    internal fun drawnRect(key: Long, placement: Placement, frame: GridFrame, isLeaving: Boolean = false): Pair<TileRect, Float> =
        drawn(plan, key, frame.rectOf(placement), if (isLeaving) plan.leavingByKey[key]?.rect else null)

    // The scroll is read here, while drawing, so tiles stay in place whichever frame the jump to the top lands on.
    private fun drawn(plan: SwitchPlan<ResolvedTile>, key: Long, laidOut: TileRect, leavingRect: TileRect?): Pair<TileRect, Float> =
        plan.frameOf(key, laidOut, progress, leavingRect, scrolledSince())

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
            val (rect, opacity) = drawn(plan, key, laidOut, leavingRect)
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

/**
 * A layout switch on start, all of it: call once while composing start, every composition, with the
 * tiles it shows ([tiles] at [placements] in [frame]); draw them through the returned [SwitchMotion]
 * ([SwitchMotion.onScreen], [SwitchMotion.modifier], and [SwitchMotion.active] to lay tiles out at
 * their new places at once).
 *
 * A switch happens in this order, and each step relies on the one before:
 * 1. Composition: the repository has published the new tiles and bumped [TileKeys.generation].
 *    [SwitchMotion.update] plans the switch from what was on screen (its old [GridFrame], and
 *    [scroll] read without observing it) and sets [SwitchMotion.active].
 * 2. Layout: tiles are placed at their new places straight away (TileHost, while active).
 * 3. Effect, a frame or two later: [onStart] (leave edit mode, close overlays), [scroll] jumps to
 *    the top, and, when the grid changes, a wait of up to [SWITCH_SETTINGS_WAIT_MILLIS] for the new
 *    layout's [settings], which land just after its tiles. Then the switch plays.
 * 4. Drawing, every frame of it: each tile's layer draws it between where it was (moved by however
 *    far [scroll] has gone since step 1) and where it is now.
 * Nothing here recomposes per frame: progress and scroll are read only while drawing.
 */
@Composable
internal fun rememberLayoutSwitch(
    keys: TileKeys,
    tiles: List<ResolvedTile>,
    placements: List<Placement>,
    frame: GridFrame,
    settings: Any,
    scroll: ScrollState,
    onStart: () -> Unit,
): SwitchMotion {
    val context = LocalContext.current
    val duration = LocalMotion.current.duration(SWITCH_MILLIS)
    val motion = remember { SwitchMotion() }
    val watch = remember { SettingsWatch() }
    motion.ended // Recompose when a switch ends, so its leaving tiles go.
    val generation = keys.generation
    // The settings start had before this switch, so the wait for the new ones can tell "already
    // arrived" from "not yet": a wait for a change that has already happened would never end.
    if (generation != watch.generation) watch.beforeSwitch = watch.latest
    watch.generation = generation
    watch.latest = settings
    motion.update(
        generation,
        tiles.indices.associate { keys.keyOf(tiles[it].spec.id) to (tiles[it] to placements[it]) },
        frame,
        gridChanges = keys.gridChanges,
        scrolledPx = { scroll.value },
    ) { Turnstile.animationsEnabled(context.contentResolver) }

    val currentSettings by rememberUpdatedState(settings)
    val currentOnStart by rememberUpdatedState(onStart)
    LaunchedEffect(generation) {
        if (!motion.takeSwitch()) return@LaunchedEffect
        currentOnStart()
        // At once: the plan follows the scroll while drawing, so tiles stay put whenever this lands.
        scroll.scrollTo(0)
        // Whether to animate was decided in update; play only clears when it planned nothing.
        motion.play(duration) {
            if (motion.waitsForGrid) {
                val before = watch.beforeSwitch
                withTimeoutOrNull(SWITCH_SETTINGS_WAIT_MILLIS) { snapshotFlow { currentSettings }.first { it != before } }
            }
        }
    }
    return motion
}

/** What start's settings were around a layout switch; see [rememberLayoutSwitch]. */
private class SettingsWatch {
    var generation = 0
    var latest: Any? = null
    var beforeSwitch: Any? = null
}
