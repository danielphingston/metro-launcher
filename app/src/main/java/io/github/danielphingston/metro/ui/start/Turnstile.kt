package io.github.danielphingston.metro.ui.start

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Timing and geometry of the Windows Phone turnstile, as pure functions: elements swing about the
 * screen's left edge, the ones further right and lower later, the tapped one last.
 */
object Turnstile {
    /** Delay per diagonal step: one column or two rows. */
    const val STEP_MS = 20

    /** The slowest an ordinary element starts, so a tall screen does not drag on. */
    const val MAX_STAGGER_MS = 120

    /** The tapped element "feathers" out after all the others. */
    const val TAPPED_DELAY_MS = 140

    /** How long one element takes to swing. */
    const val SWING_MS = 140

    /** From the first element starting to the last one landing; the same in both directions. */
    const val TOTAL_MS = TAPPED_DELAY_MS + SWING_MS

    /** The launch never waits longer than this for the exit, however slow the frames are. */
    const val EXIT_TIMEOUT_MS = 350L

    /** How long to wait for a launched app to take the screen before assuming the launch was dropped. */
    const val HOLD_TIMEOUT_MS = 1_200L

    const val MAX_DEGREES = 80f

    /**
     * Start delay for an element whose top-left corner is ([left], [top]) px; [unit] is the width
     * of one column in px. Reading order, as on the phone: the top-left goes first.
     */
    fun staggerDelay(left: Float, top: Float, unit: Float, tapped: Boolean): Int {
        if (tapped) return TAPPED_DELAY_MS
        val steps = (left / unit + top / unit / 2f).roundToInt()
        return (steps * STEP_MS).coerceIn(0, MAX_STAGGER_MS)
    }

    /** 0..1 progress of an element that starts at [startMs], [elapsedMs] into the run. */
    fun progress(elapsedMs: Float, startMs: Int): Float = ((elapsedMs - startMs) / SWING_MS).coerceIn(0f, 1f)

    /** Leaving swings 0 to +80 degrees, accelerating; returning swings -80 to 0, decelerating. */
    fun rotation(progress: Float, leaving: Boolean): Float =
        if (leaving) MAX_DEGREES * FastOutLinearInEasing.transform(progress)
        else -MAX_DEGREES * (1f - LinearOutSlowInEasing.transform(progress))

    fun alpha(progress: Float, leaving: Boolean): Float =
        if (leaving) 1f - FastOutLinearInEasing.transform(progress) else LinearOutSlowInEasing.transform(progress)

    /** False when the user turned animations off (or "remove animations", which zeroes all the scales). */
    fun animationsEnabled(resolver: ContentResolver): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
}

internal enum class TurnstilePhase { IDLE, OUT, IN }

/**
 * One clock for every element in a turnstile. Elements read [clockMs] in their draw phase, so a
 * run costs a frame callback and a redraw of the swinging layers, with no recomposition, and an
 * idle state costs nothing. Runs happen only on a launch and on the return to the launcher.
 *
 * After a completed exit the elements stay swung away ([away]) until [enter] or [reset].
 */
@Stable
class TurnstileState(private val animationsEnabled: () -> Boolean = { true }) {
    internal var phase by mutableStateOf(TurnstilePhase.IDLE)
        private set
    internal var clockMs by mutableFloatStateOf(0f)
        private set
    internal var run by mutableIntStateOf(0)
        private set

    /** Bounds (in root) of the element that was tapped, which goes last. */
    internal var tapped: Rect? = null
        private set

    /** True from a completed exit until the elements are back. */
    var away by mutableStateOf(false)
        private set

    /** True while elements are moving or swung away; a second launch is ignored then. */
    val busy: Boolean get() = phase != TurnstilePhase.IDLE

    /**
     * Swings the elements out, [tappedBounds] last, and returns true if they are now away; false
     * if animations are off or the run was reset. Takes at most [Turnstile.EXIT_TIMEOUT_MS]. If the
     * caller is cancelled, the elements are restored.
     */
    suspend fun exit(tappedBounds: Rect?): Boolean {
        if (!animationsEnabled()) return false
        val id = begin(TurnstilePhase.OUT, tappedBounds)
        try {
            withTimeoutOrNull(Turnstile.EXIT_TIMEOUT_MS) { tick(id) }
        } catch (e: CancellationException) {
            if (run == id) reset()
            throw e
        }
        if (run != id) return false
        clockMs = Turnstile.TOTAL_MS.toFloat()
        away = true
        return true
    }

    /** Swings the elements back in, in reverse order, then goes idle. Does nothing unless [away]. */
    suspend fun enter() {
        if (!away) return
        if (!animationsEnabled()) return reset()
        val id = begin(TurnstilePhase.IN, tapped)
        try {
            tick(id)
        } finally {
            // Cancelled or done: never leave anything rotated. A newer run owns the state if run moved on.
            if (run == id) reset()
        }
    }

    /** Puts everything back at once and cancels any run in progress. */
    fun reset() {
        run++
        phase = TurnstilePhase.IDLE
        clockMs = 0f
        away = false
    }

    /** Freezes a run at [ms], for screenshots. */
    internal fun seek(phase: TurnstilePhase, ms: Float, tappedBounds: Rect? = null) {
        begin(phase, tappedBounds)
        clockMs = ms
    }

    private fun begin(next: TurnstilePhase, tappedBounds: Rect?): Int {
        run++
        tapped = tappedBounds
        clockMs = 0f
        phase = next
        return run
    }

    /** Advances [clockMs] once per frame until the run is over or superseded. */
    private suspend fun tick(id: Int) {
        val start = withFrameNanos { it }
        while (run == id) {
            val elapsed = (withFrameNanos { it } - start) / 1_000_000f
            clockMs = elapsed.coerceAtMost(Turnstile.TOTAL_MS.toFloat())
            if (elapsed >= Turnstile.TOTAL_MS) return
        }
    }
}

/** The state the launcher's start screen and app list swing with; the default never plays. */
val LocalTurnstile = staticCompositionLocalOf { TurnstileState() }

/**
 * Plays the swing back in when the launcher is RESUMED again after an exit. If the launched app
 * never took the screen (a dropped launch), the elements come back after [Turnstile.HOLD_TIMEOUT_MS].
 * A [TurnstileState.reset] (Home on another page) cancels it.
 */
@Composable
fun TurnstileReturn(state: TurnstileState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
        snapshotFlow { state.away }.collectLatest { away ->
            if (!away) return@collectLatest
            withTimeoutOrNull(Turnstile.HOLD_TIMEOUT_MS) {
                lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
            }
            lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
            state.enter()
        }
    }
}

/** Where an element sits during a run, worked out once per run from its bounds. */
private class TurnstileSlot {
    var run = -1
    var onScreen = false
    var delay = 0
    var pivotX = 0f
    var pivotY = 0f

    fun prepare(state: TurnstileState, viewWidth: Int, viewHeight: Int, bounds: Rect?) {
        if (run == state.run) return
        // Not laid out yet: try again on the next draw rather than caching "off screen".
        if (bounds == null) {
            onScreen = false
            return
        }
        run = state.run
        onScreen = bounds.right > 0f && bounds.left < viewWidth && bounds.bottom > 0f && bounds.top < viewHeight
        if (!onScreen) return
        delay = Turnstile.staggerDelay(bounds.left, bounds.top, viewWidth / 4f, tapped = bounds == state.tapped)
        // Hinge on the screen's left edge, level with the screen's middle, not on the element.
        pivotX = -bounds.left
        pivotY = viewHeight / 2f - bounds.top
    }
}

/**
 * Swings this element about the screen's left edge during a [state] run. [boundsProvider] gives
 * its bounds in the root, as `boundsInRoot()` does; elements off screen (another pager page)
 * stay put. [key] identifies the element across recompositions. Without a run this only reads one
 * state value in the draw phase.
 */
fun Modifier.turnstile(state: TurnstileState, key: Any, boundsProvider: () -> Rect?): Modifier = composed {
    val view = LocalView.current
    val slot = remember(state, key) { TurnstileSlot() }
    val bounds by rememberUpdatedState(boundsProvider)
    graphicsLayer {
        val phase = state.phase
        if (phase == TurnstilePhase.IDLE) return@graphicsLayer
        slot.prepare(state, view.width, view.height, bounds())
        if (!slot.onScreen) return@graphicsLayer
        val leaving = phase == TurnstilePhase.OUT
        // Coming back, the order is reversed: what left last returns first.
        val start = if (leaving) slot.delay else Turnstile.TAPPED_DELAY_MS - slot.delay
        val progress = Turnstile.progress(state.clockMs, start)
        rotationY = Turnstile.rotation(progress, leaving)
        alpha = Turnstile.alpha(progress, leaving)
        transformOrigin = TransformOrigin(slot.pivotX / size.width, slot.pivotY / size.height)
        cameraDistance = 24f * density
    }
}

/** [turnstile] for an element that does not track its own bounds. */
fun Modifier.turnstile(state: TurnstileState, key: Any): Modifier = composed {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    onGloballyPositioned { bounds = it.boundsInRoot() }.turnstile(state, key) { bounds }
}

private const val CONTINUUM_DEGREES = 12f

/**
 * A subtle swing for [page] while the pager is dragged or settling: the page leans away as it
 * comes in and toward you as it leaves, hinged on its left edge. Draw phase only, flat at rest.
 */
fun Modifier.continuum(pager: PagerState, page: Int): Modifier = graphicsLayer {
    val offset = (page - pager.currentPage - pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
    if (offset == 0f) return@graphicsLayer
    rotationY = offset * CONTINUUM_DEGREES
    transformOrigin = TransformOrigin(0f, 0.5f)
    cameraDistance = 24f * density
}
