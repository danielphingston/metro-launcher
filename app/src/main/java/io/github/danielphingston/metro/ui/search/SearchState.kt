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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo
import kotlin.math.abs

/** Whether the search overlay is open, what was typed, and the app whose menu is showing. */
@Stable
class SearchState {
    var isOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    /** App whose long-press menu is open. */
    var menu by mutableStateOf<AppInfo?>(null)

    /** Opens the overlay with an empty field. */
    fun open() {
        query = ""
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

/** How far past the top the start screen must be pulled down to open search. */
private val PullThreshold = 72.dp

/**
 * Notices a downward drag past the top of [scroll] and calls [onPull] once per gesture. Put it
 * (with `Modifier.nestedScroll`) on a parent of the scroll container: it only sees what the list
 * could not use itself, never consumes anything, and ignores drags that began further down.
 */
@Composable
fun rememberPullToSearch(scroll: ScrollState, enabled: Boolean, onPull: () -> Unit): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { PullThreshold.toPx() }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnPull by rememberUpdatedState(onPull)
    return remember(scroll, threshold) {
        object : NestedScrollConnection {
            /** Null between gestures; then whether the gesture started at the very top. */
            private var fromTop: Boolean? = null
            private var pulled = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && fromTop == null) {
                    fromTop = scroll.value == 0
                    pulled = 0f
                }
                // Dragging back up cancels the pull.
                if (available.y < 0) pulled = 0f
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || fromTop != true || !currentEnabled) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                pulled += available.y
                if (pulled >= threshold) {
                    fromTop = false
                    pulled = 0f
                    currentOnPull()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                fromTop = null
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
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
