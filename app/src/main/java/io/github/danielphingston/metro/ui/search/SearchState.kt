package io.github.danielphingston.metro.ui.search

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo

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
