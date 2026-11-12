package io.github.danielphingston.metro.ui.gestures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.dp

/** Distances of the two-finger gestures. */
object GestureDistances {
    val Swipe = 72.dp
    val Pinch = 48.dp

    /** How far a finger may have moved on its own before a second one lands for it to still count. */
    val Slop = 12.dp

    /** Android's own distance between the taps of a double tap. */
    val DoubleTapSlop = 100.dp
}

/**
 * Reports two-finger swipes and pinches in on the area, via [TwoFingerRecognizer]. It watches
 * before the children (initial pass) and, once two fingers are down together, consumes their
 * movement so the page does not scroll while the gesture is recognised; with one finger it never
 * consumes anything, so scrolling, pull to search, paging and dragging tiles are untouched. Off
 * (nothing is installed) when [enabled] is false, e.g. in edit mode or with no two-finger gesture set.
 */
@Composable
fun Modifier.twoFingerGestures(enabled: Boolean, onGesture: (TwoFingerGesture) -> Unit): Modifier {
    val density = LocalDensity.current
    val current by rememberUpdatedState(onGesture)
    val recognizer = remember(density) {
        with(density) {
            TwoFingerRecognizer(
                swipeDistance = GestureDistances.Swipe.toPx(),
                pinchDistance = GestureDistances.Pinch.toPx(),
                slop = GestureDistances.Slop.toPx(),
            )
        }
    }
    if (!enabled) return this
    return pointerInput(recognizer) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            recognizer.down(first.id.value, first.position.x, first.position.y)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    var matched: TwoFingerGesture? = null
                    for (change in event.changes) {
                        val id = change.id.value
                        when {
                            change.changedToDownIgnoreConsumed() -> recognizer.down(id, change.position.x, change.position.y)
                            change.changedToUpIgnoreConsumed() -> recognizer.up(id)
                            change.positionChangedIgnoreConsumed() -> recognizer.move(id, change.position.x, change.position.y)?.let { matched = it }
                        }
                    }
                    if (recognizer.tracking) event.changes.forEach { if (it.pressed) it.consume() }
                    matched?.let(current)
                } while (event.changes.any { it.pressed })
            } finally {
                recognizer.cancel()
            }
        }
    }
}
