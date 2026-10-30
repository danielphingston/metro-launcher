package io.github.danielphingston.metro.ui.search

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.LauncherSettings
import kotlin.math.abs

/** Whether the search overlay is open, what was typed, and the app whose menu is showing. */
@Stable
class SearchState {
    var isOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    /** Where the cursor goes when [query] was replaced by a backspace on a keyword chip: right after the keyword. Null: the end. */
    var queryCursor by mutableStateOf<Int?>(null)

    /** App whose long-press menu is open. */
    var menu by mutableStateOf<AppInfo?>(null)

    /** Opens the overlay with an empty field. */
    fun open() {
        query = ""
        queryCursor = null
        menu = null
        isOpen = true
    }

    /** Closes the overlay; returns true if it was open. The query stays until the next [open], so the fade-out keeps its content. */
    fun close(): Boolean {
        val was = isOpen
        isOpen = false
        menu = null
        return was
    }

    /** Back closes the app menu first, then the overlay; returns true if back was consumed. */
    fun handleBack(): Boolean {
        if (menu != null) {
            menu = null
            return true
        }
        return close()
    }
}

/**
 * Tracks a pull-down past the top of the start screen and says when releasing should open search.
 * Per gesture: [begin] with whether the list was already at its very top (only then does the
 * gesture count), [track] with the finger's net movement since touch-down, [drag] with each scroll
 * the list could not use, and [release] when the finger lifts. Dragging back up gives the pull back,
 * so letting go below [threshold] opens nothing.
 */
internal class PullToOpen(private val threshold: Float) {
    private var armed = false
    private var pulled = 0f
    private var over = false
    private var dx = 0f
    private var dy = 0f

    fun begin(atTop: Boolean) {
        armed = atTop
        pulled = 0f
        over = false
        dx = 0f
        dy = 0f
    }

    /** Net finger movement since touch-down; a gesture that went further sideways than down is not a pull. */
    fun track(dx: Float, dy: Float) {
        this.dx = dx
        this.dy = dy
    }

    /** [dy] is leftover scroll (positive is down, negative gives pull back); true each time the pull reaches the threshold. */
    fun drag(dy: Float): Boolean {
        if (!armed) return false
        pulled = (pulled + dy).coerceAtLeast(0f)
        if (pulled < threshold || sideways) {
            over = pulled >= threshold
            return false
        }
        val crossed = !over
        over = true
        return crossed
    }

    /** The finger lifted: true if the pull is long enough and mostly vertical. Ends the gesture either way. */
    fun release(): Boolean {
        val open = armed && pulled >= threshold && !sideways
        armed = false
        pulled = 0f
        over = false
        return open
    }

    private val sideways get() = abs(dx) > abs(dy)
}

/**
 * Opens search when the start screen is pulled down past its top by [pullDistance] and let go.
 * Reaching the distance gives a haptic tick (the cue that releasing will open search); dragging back
 * above it before lifting cancels. Only touch counts, only gestures that began with [scroll] already
 * at its very top (a fling that scrolls up and overshoots never opens it), and not mostly sideways
 * ones (a swipe to the app list). Put it on a parent of the scroll container: it only sees what the
 * list could not use itself, and never consumes anything.
 */
@Composable
fun Modifier.pullToSearch(
    scroll: ScrollState,
    enabled: Boolean,
    pullDistance: Dp = LauncherSettings.DEFAULT_PULL_DISTANCE.dp,
    onPull: () -> Unit,
): Modifier {
    val threshold = with(LocalDensity.current) { pullDistance.toPx() }
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnPull by rememberUpdatedState(onPull)
    val pull = remember(threshold) { PullToOpen(threshold) }
    val connection = remember(pull) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Dragging back up gives the pull back.
                if (source == NestedScrollSource.UserInput && available.y < 0f) pull.drag(available.y)
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && pull.drag(available.y) && currentEnabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.release() && currentEnabled) currentOnPull()
                return Velocity.Zero
            }
        }
    }
    return pointerInput(pull, scroll) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pull.begin(atTop = scroll.value == 0)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                pull.track(change.position.x - down.position.x, change.position.y - down.position.y)
            } while (event.changes.any { it.pressed })
        }
    }.nestedScroll(connection)
}

/** How far up the panel must be dragged to close search. */
private val SwipeUpThreshold = 72.dp

/**
 * Counts an upward drag that the content could not use and says when it is long enough to close
 * search. Per gesture: [begin] with whether the list was already at its end (only then does the
 * gesture count), [drag] with each movement, [end] when the finger is done. Dragging back down
 * restarts the count, so a closing swipe has to be one steady upward stroke.
 */
internal class SwipeUpToClose(private val threshold: Float) {
    /** Null between gestures; then whether this gesture may close. */
    private var armed: Boolean? = null
    private var travelled = 0f

    fun begin(atEnd: Boolean) {
        if (armed != null) return
        armed = atEnd
        travelled = 0f
    }

    /** [dy] is the vertical movement (negative is up); returns true once, when the swipe is long enough. */
    fun drag(dy: Float): Boolean {
        if (armed != true) return false
        if (dy >= 0f) {
            travelled = 0f
            return false
        }
        travelled -= dy
        if (travelled < threshold) return false
        armed = false
        return true
    }

    fun end() {
        armed = null
        travelled = 0f
    }
}

/**
 * Closes search on an upward touch drag past the end of [list] (or with nothing to scroll). Put it
 * (with `Modifier.nestedScroll`) on a parent of the list: it only sees what the list could not use
 * itself, so scrolling the results is never cut short, and it never consumes anything. A drag that
 * starts mid-list does not count, even if it scrolls to the end; the user has to swipe again.
 */
@Composable
internal fun rememberSwipeUpToClose(list: LazyListState, onClose: () -> Unit): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { SwipeUpThreshold.toPx() }
    val currentOnClose by rememberUpdatedState(onClose)
    return remember(list, threshold) {
        val swipe = SwipeUpToClose(threshold)
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    swipe.begin(atEnd = !list.canScrollForward)
                    if (available.y > 0f) swipe.drag(available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && swipe.drag(available.y)) currentOnClose()
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                swipe.end()
                return Velocity.Zero
            }
        }
    }
}

/**
 * Closes search on an upward touch drag that starts on this element (the search field). Only
 * watches: the field still gets its taps, cursor and selection drags.
 */
@Composable
internal fun Modifier.swipeUpToClose(onClose: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { SwipeUpThreshold.toPx() }
    val currentOnClose by rememberUpdatedState(onClose)
    return pointerInput(threshold) {
        val swipe = SwipeUpToClose(threshold)
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            swipe.begin(atEnd = true)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                val delta = change.positionChange()
                // Mostly sideways movement is a cursor or selection drag, not a swipe.
                if (abs(delta.y) > abs(delta.x) && swipe.drag(delta.y)) currentOnClose()
            } while (event.changes.any { it.pressed })
            swipe.end()
        }
    }
}
