package io.github.danielphingston.metro.ui.start

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.danielphingston.metro.data.LaunchStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/** How an element looks part-way through a run; [translationDp] is down, positive. */
@Immutable
data class LaunchPose(
    val rotationY: Float = 0f,
    val translationDp: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
)

/** What the opened app's window does as it appears; see [AppLauncher.transition]. */
enum class AppWindow { FADE, SLIDE_UP, ZOOM_IN, CLIP_REVEAL }

/**
 * One launch animation style as data: when each element starts ([delay], [tappedDelayMs]), how long
 * it takes ([swingMs]), where it pivots ([pivot]) and how it looks at a given amount of "away"
 * ([pose]; 0 in place, 1 gone), plus how the app's window appears.
 */
@Immutable
class LaunchSpec(
    val swingMs: Int,
    val tappedDelayMs: Int,
    val maxStaggerMs: Int,
    val appWindow: AppWindow,
    /** Start delay (before the [maxStaggerMs] cap) of an element whose top-left is (left, top); unit is one column. */
    val delay: (left: Float, top: Float, unit: Float) -> Int,
    /** The element's look at [amount] away; [leaving] tells the direction a swing turns. */
    val pose: (amount: Float, leaving: Boolean) -> LaunchPose,
    /** The pivot, in the element's own pixels, from its [bounds] and the [tapped] element's, in the root. */
    val pivot: (bounds: Rect, tapped: Rect?, viewWidth: Float, viewHeight: Float) -> Offset,
) {
    /** From the first element starting to the last one landing; the same in both directions. */
    val totalMs: Int get() = tappedDelayMs + swingMs

    /** The delay of an ordinary element or, [tapped], of the tapped one (always last). */
    fun startDelay(left: Float, top: Float, unit: Float, tapped: Boolean): Int =
        if (tapped) tappedDelayMs else delay(left, top, unit).coerceIn(0, maxStaggerMs)

    /** Where the element is in its swing: 0..1 for an element starting at [startMs], [elapsedMs] in. */
    fun progress(elapsedMs: Float, startMs: Int): Float = ((elapsedMs - startMs) / swingMs).coerceIn(0f, 1f)

    /** The look of an element at [progress] of its swing. */
    fun poseAt(progress: Float, leaving: Boolean): LaunchPose =
        pose(Turnstile.away(progress, leaving), leaving)

    /** When an element that left at [delay] comes back: what left last returns first. */
    fun returnStart(delay: Int): Int = tappedDelayMs - delay
}

/**
 * Timing and geometry of the Windows Phone turnstile and the other launch styles, as pure
 * functions: in the turnstile elements swing about the screen's left edge, the ones further right
 * and lower later, the tapped one last.
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

    /** The launch never waits longer than this for the exit at normal speed, however slow the frames are. */
    const val EXIT_TIMEOUT_MS = 350L

    /**
     * How long to wait for a launched app to take the screen before assuming the launch was dropped.
     * A cold start of a heavy app can take over a second, and swinging back in under it looks broken.
     */
    const val HOLD_TIMEOUT_MS = 2_500L

    /** A second tap this soon after a launch is ignored. */
    const val DOUBLE_LAUNCH_MS = 600L

    /** With "open apps during the animation" the app starts when the exit is this far along. */
    const val OPEN_EARLY_FRACTION = 0.55f

    const val MAX_DEGREES = 80f

    /** How far SLIDE moves an element down, and how far ZOOM shrinks one. */
    const val SLIDE_DP = 40f
    const val ZOOM_SCALE = 0.85f

    /**
     * Start delay for an element whose top-left corner is ([left], [top]) px; [unit] is the width
     * of one column in px. Reading order, as on the phone: the top-left goes first.
     */
    fun staggerDelay(left: Float, top: Float, unit: Float, tapped: Boolean): Int =
        spec(LaunchStyle.TURNSTILE).startDelay(left, top, unit, tapped)

    private fun diagonal(left: Float, top: Float, unit: Float) = (left / unit + top / unit / 2f).roundToInt() * STEP_MS

    /** Whole rows go together: one step per half column of height, whatever the left. */
    private fun rows(left: Float, top: Float, unit: Float) = (top / (unit / 2f)).roundToInt() * ROW_STEP_MS

    private const val ROW_STEP_MS = 15

    /**
     * Whether [rect] is the [tapped] element. Bounds are read lazily and include a press tilt in
     * progress, so they only match closely: the centres agree to within a quarter of the size.
     */
    fun isTapped(rect: Rect, tapped: Rect?): Boolean =
        tapped != null && (rect.center - tapped.center).getDistance() < minOf(tapped.width, tapped.height) / 4f

    /** 0..1 progress of an element that starts at [startMs], [elapsedMs] into the run (the turnstile's swing). */
    fun progress(elapsedMs: Float, startMs: Int): Float = ((elapsedMs - startMs) / SWING_MS).coerceIn(0f, 1f)

    /** How far gone an element is: leaving accelerates away, returning decelerates home. */
    fun away(progress: Float, leaving: Boolean): Float =
        if (leaving) FastOutLinearInEasing.transform(progress) else 1f - LinearOutSlowInEasing.transform(progress)

    /** Leaving swings 0 to +80 degrees, accelerating; returning swings -80 to 0, decelerating. */
    fun rotation(progress: Float, leaving: Boolean): Float =
        (if (leaving) MAX_DEGREES else -MAX_DEGREES) * away(progress, leaving)

    fun alpha(progress: Float, leaving: Boolean): Float = 1f - away(progress, leaving)

    private val turnstile = LaunchSpec(
        swingMs = SWING_MS,
        tappedDelayMs = TAPPED_DELAY_MS,
        maxStaggerMs = MAX_STAGGER_MS,
        appWindow = AppWindow.FADE,
        delay = ::diagonal,
        pose = { away, leaving ->
            LaunchPose(rotationY = (if (leaving) MAX_DEGREES else -MAX_DEGREES) * away, alpha = 1f - away)
        },
        // Hinge on the screen's left edge, level with the screen's middle, not on the element.
        pivot = { bounds, _, _, viewHeight -> Offset(-bounds.left, viewHeight / 2f - bounds.top) },
    )

    private val specs: Map<LaunchStyle, LaunchSpec> = mapOf(
        LaunchStyle.TURNSTILE to turnstile,
        LaunchStyle.TURNSTILE_ROWS to LaunchSpec(
            swingMs = SWING_MS,
            tappedDelayMs = TAPPED_DELAY_MS,
            maxStaggerMs = MAX_STAGGER_MS,
            appWindow = AppWindow.FADE,
            delay = ::rows,
            pose = turnstile.pose,
            pivot = turnstile.pivot,
        ),
        LaunchStyle.SLIDE to LaunchSpec(
            swingMs = 180,
            tappedDelayMs = 150,
            maxStaggerMs = MAX_STAGGER_MS,
            appWindow = AppWindow.SLIDE_UP,
            delay = ::rows,
            pose = { away, _ -> LaunchPose(translationDp = SLIDE_DP * away, alpha = 1f - away) },
            pivot = { bounds, _, _, _ -> Offset(bounds.width / 2f, bounds.height / 2f) },
        ),
        LaunchStyle.ZOOM to LaunchSpec(
            swingMs = 180,
            tappedDelayMs = 60,
            maxStaggerMs = 0,
            appWindow = AppWindow.ZOOM_IN,
            delay = { _, _, _ -> 0 },
            pose = { away, _ -> LaunchPose(scale = 1f - (1f - ZOOM_SCALE) * away, alpha = 1f - away) },
            // Everything shrinks toward the tapped element (the screen's middle without one).
            pivot = { bounds, tapped, viewWidth, viewHeight ->
                val target = tapped?.center ?: Offset(viewWidth / 2f, viewHeight / 2f)
                Offset(target.x - bounds.left, target.y - bounds.top)
            },
        ),
        LaunchStyle.FADE to LaunchSpec(
            swingMs = 180,
            tappedDelayMs = 0,
            maxStaggerMs = 0,
            appWindow = AppWindow.FADE,
            delay = { _, _, _ -> 0 },
            pose = { away, _ -> LaunchPose(alpha = 1f - away) },
            pivot = { bounds, _, _, _ -> Offset(bounds.width / 2f, bounds.height / 2f) },
        ),
        // The launcher stays as it is; the system reveals the app from the tapped element.
        LaunchStyle.NONE to LaunchSpec(
            swingMs = 1,
            tappedDelayMs = 0,
            maxStaggerMs = 0,
            appWindow = AppWindow.CLIP_REVEAL,
            delay = { _, _, _ -> 0 },
            pose = { _, _ -> LaunchPose() },
            pivot = { bounds, _, _, _ -> Offset(bounds.width / 2f, bounds.height / 2f) },
        ),
    )

    fun spec(style: LaunchStyle): LaunchSpec = specs.getValue(style)

    /**
     * Real time per designed time: [speed] 2 is twice as fast, and the system's animator scale
     * ([systemScale], 2 = twice as slow) stretches it further.
     */
    fun timeScale(speed: Float, systemScale: Float): Float = systemScale / speed.coerceAtLeast(0.01f)

    /** The wait for an exit grows with slow animations, or the timeout would cut them short. */
    fun exitTimeoutMs(timeScale: Float): Long = (EXIT_TIMEOUT_MS * max(1f, timeScale)).toLong()

    /** When, in designed ms, the app is started with "open apps during the animation". */
    fun openAtMs(spec: LaunchSpec): Float = spec.totalMs * OPEN_EARLY_FRACTION

    /** The system's animator duration scale: 0 is off, 1 normal. */
    fun systemScale(resolver: ContentResolver): Float =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceAtLeast(0f)

    /** False when the user turned animations off (or "remove animations", which zeroes all the scales). */
    fun animationsEnabled(resolver: ContentResolver): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
}

/**
 * Ignores a launch that follows another within [Turnstile.DOUBLE_LAUNCH_MS]: with no turnstile
 * running (animations off, or no launcher animation) nothing else marks the launcher busy.
 */
class LaunchGuard(private val windowMs: Long = Turnstile.DOUBLE_LAUNCH_MS) {
    private var last = Long.MIN_VALUE

    /** True if a launch at [nowMs] may go ahead; it then starts a new window. */
    fun tryLaunch(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last in 0 until windowMs) return false
        last = nowMs
        return true
    }
}

/**
 * The LayoutCoordinates of an element, kept without reading them: scrolling moves the element
 * every frame, and the bounds are needed only when a launch starts or a run is prepared.
 */
class BoundsTracker {
    private var coordinates: LayoutCoordinates? = null

    fun update(layout: LayoutCoordinates) {
        coordinates = layout
    }

    /** The bounds in the root now, or null before the first layout or once the element is gone. */
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

/** Remembers a [BoundsTracker] and feeds it from this element's layout. */
fun Modifier.trackBounds(tracker: BoundsTracker): Modifier = onGloballyPositioned { tracker.update(it) }

internal enum class TurnstilePhase { IDLE, OUT, IN }

/**
 * One clock for every element in a run. Elements read [clockMs] in their draw phase, so a run
 * costs a frame callback and a redraw of the moving layers, with no recomposition, and an idle
 * state costs nothing. Runs happen only on a launch and on the return to the launcher. The clock
 * counts designed milliseconds: [timeScale] stretches or squeezes them into real time.
 *
 * After a completed exit the elements stay away ([away]) until [enter] or [reset].
 */
@Stable
class TurnstileState(
    /** Real time per designed time for the next run; see [Turnstile.timeScale]. */
    private val timeScale: () -> Float = { 1f },
    private val animationsEnabled: () -> Boolean = { true },
) {
    internal var phase by mutableStateOf(TurnstilePhase.IDLE)
        private set
    internal var clockMs by mutableFloatStateOf(0f)
        private set
    internal var run by mutableIntStateOf(0)
        private set

    /** The style of the current (or last) run; a return plays the style the exit used. */
    internal var spec by mutableStateOf(Turnstile.spec(LaunchStyle.TURNSTILE))
        private set

    /** Bounds (in root) of the element that was tapped, which goes last. */
    internal var tapped: Rect? = null
        private set

    /** True from a completed exit until the elements are back. */
    var away by mutableStateOf(false)
        private set

    /** True while elements are moving or away; a second launch is ignored then. */
    val busy: Boolean get() = phase != TurnstilePhase.IDLE

    /**
     * Moves the elements out in [style], [tappedBounds] last, and returns true if they are now
     * away; false if animations are off, the style has no launcher animation, or the run was reset.
     * Takes at most [Turnstile.exitTimeoutMs]. With [openEarly], [onOpen] is called once when the
     * exit is [Turnstile.OPEN_EARLY_FRACTION] done, and the exit still plays to its end. If the
     * caller is cancelled, the elements are restored.
     */
    suspend fun exit(
        tappedBounds: Rect?,
        style: LaunchStyle = LaunchStyle.TURNSTILE,
        openEarly: Boolean = false,
        onOpen: () -> Unit = {},
    ): Boolean {
        if (style == LaunchStyle.NONE || !animationsEnabled()) return false
        val scale = timeScale()
        val id = begin(TurnstilePhase.OUT, tappedBounds, Turnstile.spec(style))
        var opened = !openEarly
        try {
            withTimeoutOrNull(Turnstile.exitTimeoutMs(scale)) {
                tick(id, scale) { ms ->
                    if (!opened && ms >= Turnstile.openAtMs(spec)) {
                        opened = true
                        onOpen()
                    }
                }
            }
        } catch (e: CancellationException) {
            if (run == id) reset()
            throw e
        }
        if (run != id) return false
        clockMs = spec.totalMs.toFloat()
        away = true
        return true
    }

    /**
     * Moves the elements back in, in reverse order, then goes idle; with [animate] off they just
     * snap back. Does nothing unless [away].
     */
    suspend fun enter(animate: Boolean = true) {
        if (!away) return
        if (!animate || !animationsEnabled()) return reset()
        val scale = timeScale()
        val id = begin(TurnstilePhase.IN, tapped, spec)
        try {
            tick(id, scale)
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
    internal fun seek(
        phase: TurnstilePhase,
        ms: Float,
        tappedBounds: Rect? = null,
        style: LaunchStyle = LaunchStyle.TURNSTILE,
    ) {
        begin(phase, tappedBounds, Turnstile.spec(style))
        clockMs = ms
    }

    private fun begin(next: TurnstilePhase, tappedBounds: Rect?, style: LaunchSpec): Int {
        run++
        tapped = tappedBounds
        spec = style
        clockMs = 0f
        phase = next
        return run
    }

    /** Advances [clockMs] once per frame until the run is over or superseded; [onClock] sees every value. */
    private suspend fun tick(id: Int, scale: Float, onClock: (Float) -> Unit = {}) {
        val total = spec.totalMs.toFloat()
        val start = withFrameNanos { it }
        while (run == id) {
            val elapsed = (withFrameNanos { it } - start) / 1_000_000f / scale.coerceAtLeast(0.01f)
            clockMs = elapsed.coerceAtMost(total)
            onClock(clockMs)
            if (elapsed >= total) return
        }
    }
}

/** The state the launcher's start screen and app list move with; the default never plays. */
val LocalTurnstile = staticCompositionLocalOf { TurnstileState() }

/**
 * Plays the animation back in when the launcher is RESUMED again after an exit (snaps back when
 * [animate] is off). If the launched app never took the screen (a dropped launch), the elements
 * come back after [Turnstile.HOLD_TIMEOUT_MS]. A [TurnstileState.reset] (Home on another page)
 * cancels it.
 */
@Composable
fun TurnstileReturn(state: TurnstileState, animate: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAnimate by rememberUpdatedState(animate)
    LaunchedEffect(state, lifecycle) {
        snapshotFlow { state.away }.collectLatest { away ->
            if (!away) return@collectLatest
            withTimeoutOrNull(Turnstile.HOLD_TIMEOUT_MS) {
                lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
            }
            lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
            state.enter(currentAnimate)
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

    /** [bounds] is asked for only when the run changes: not on every frame of a scroll or a run. */
    fun prepare(state: TurnstileState, viewWidth: Int, viewHeight: Int, bounds: () -> Rect?) {
        if (run == state.run) return
        val rect = bounds()
        // Not laid out yet: try again on the next draw rather than caching "off screen".
        if (rect == null) {
            onScreen = false
            return
        }
        run = state.run
        onScreen = rect.right > 0f && rect.left < viewWidth && rect.bottom > 0f && rect.top < viewHeight
        if (!onScreen) return
        val spec = state.spec
        delay = spec.startDelay(rect.left, rect.top, viewWidth / 4f, tapped = Turnstile.isTapped(rect, state.tapped))
        val pivot = spec.pivot(rect, state.tapped, viewWidth.toFloat(), viewHeight.toFloat())
        pivotX = pivot.x
        pivotY = pivot.y
    }
}

/**
 * Moves this element during a [state] run. [boundsProvider] gives its bounds in the root, as
 * `boundsInRoot()` does, and is called only when a run is prepared; elements off screen (another
 * pager page) stay put. [key] identifies the element across recompositions. Without a run this
 * only reads one state value in the draw phase.
 */
fun Modifier.turnstile(state: TurnstileState, key: Any, boundsProvider: () -> Rect?): Modifier = composed {
    val view = LocalView.current
    val slot = remember(state, key) { TurnstileSlot() }
    val bounds by rememberUpdatedState(boundsProvider)
    graphicsLayer {
        val phase = state.phase
        if (phase == TurnstilePhase.IDLE) return@graphicsLayer
        slot.prepare(state, view.width, view.height) { bounds() }
        if (!slot.onScreen) return@graphicsLayer
        val spec = state.spec
        val leaving = phase == TurnstilePhase.OUT
        // Coming back, the order is reversed: what left last returns first.
        val start = if (leaving) slot.delay else spec.returnStart(slot.delay)
        val pose = spec.poseAt(spec.progress(state.clockMs, start), leaving)
        rotationY = pose.rotationY
        translationY = pose.translationDp * density
        scaleX = pose.scale
        scaleY = pose.scale
        alpha = pose.alpha
        if (size.width > 0f && size.height > 0f) {
            transformOrigin = TransformOrigin(slot.pivotX / size.width, slot.pivotY / size.height)
        }
        cameraDistance = 24f * density
    }
}

/** [turnstile] for an element that does not track its own bounds. */
fun Modifier.turnstile(state: TurnstileState, key: Any): Modifier = composed {
    val tracker = remember { BoundsTracker() }
    trackBounds(tracker).turnstile(state, key) { tracker.bounds() }
}

private const val CONTINUUM_DEGREES = 12f

/**
 * A subtle swing for [page] while the pager is dragged or settling: the page leans away as it
 * comes in and toward you as it leaves, hinged on its left edge. Draw phase only, flat at rest,
 * and flat always when [enabled] is off.
 */
fun Modifier.continuum(pager: PagerState, page: Int, enabled: Boolean = true): Modifier = graphicsLayer {
    if (!enabled) return@graphicsLayer
    val offset = (page - pager.currentPage - pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
    if (offset == 0f) return@graphicsLayer
    rotationY = offset * CONTINUUM_DEGREES
    transformOrigin = TransformOrigin(0f, 0.5f)
    cameraDistance = 24f * density
}
