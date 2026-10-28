package io.github.danielphingston.metro.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a scroll animation that the user may interrupt. Touching the screen while it runs makes
 * the animation throw a CancellationException that belongs to the animation alone; letting it
 * escape would end the coroutine that collects the Home and pin events, and those buttons
 * would stop working until the process restarts. Only a real cancellation of the calling
 * coroutine is rethrown.
 */
internal suspend fun interruptible(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

/**
 * Scrolls to the bottom of the content once it has grown past [before] (the scroll range when
 * the change that adds content was made), waiting at most [timeoutMillis]: a tile pinned just now
 * is not laid out yet, so its range is still the old one. Without growth (the tile fit in
 * existing space) it scrolls to the unchanged end after the timeout.
 */
internal suspend fun ScrollState.animateToGrownEnd(before: Int, timeoutMillis: Long = 500) {
    if (maxValue <= before) withTimeoutOrNull(timeoutMillis) { snapshotFlow { maxValue }.first { it > before } }
    animateScrollTo(maxValue)
}
