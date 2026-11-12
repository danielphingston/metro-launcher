package io.github.danielphingston.metro.ui.gestures

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Recognises a double tap from the taps it is told about: the second must start soon enough
 * after the first ended and land close to it. Pure, so the timing is testable; the caller says
 * whether each tap is one a double tap may count (not in edit mode, not with a menu open...), and
 * an ineligible tap breaks the pair, so closing something with a tap never starts a double tap.
 */
class DoubleTapRecognizer(
    private val slop: Float,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private var lastUp = -1L
    private var lastX = 0f
    private var lastY = 0f

    /** A finished tap that went down at [downTime] and up at [upTime]; returns true when it completes a double tap. */
    fun onTap(downTime: Long, upTime: Long, x: Float, y: Float, eligible: Boolean = true): Boolean {
        if (!eligible) {
            reset()
            return false
        }
        val double = lastUp >= 0 && downTime - lastUp in 0..timeoutMs && hypot(x - lastX, y - lastY) <= slop
        if (double) {
            reset()
        } else {
            lastUp = upTime
            lastX = x
            lastY = y
        }
        return double
    }

    /** Forgets the pending first tap. */
    fun reset() {
        lastUp = -1L
    }

    companion object {
        /** The window Android uses between the two taps of a double tap. */
        const val TIMEOUT_MS = 300L
    }
}

enum class TwoFingerGesture { SWIPE_DOWN, SWIPE_UP, PINCH_IN }

/**
 * Tells two-finger swipes and a pinch in from the pointers it is fed, in pixels. A gesture is
 * exactly two fingers that stay down together; a lone finger, a third finger, a finger that lifts
 * early, a finger that had already travelled (scrolling) before the second landed, a sideways
 * movement or fingers spreading apart all fail it until every finger is up. A swipe needs both
 * fingers to move the same way, mostly vertically; a pinch needs the fingers to close in without
 * the pair travelling. A match is reported once, as soon as the distance is reached.
 */
class TwoFingerRecognizer(
    private val swipeDistance: Float,
    private val pinchDistance: Float,
    private val slop: Float,
) {
    private class Finger(var x: Float, var y: Float) {
        val startX = x
        val startY = y
        var baseX = x
        var baseY = y
    }

    private val fingers = LinkedHashMap<Long, Finger>()
    private var failed = false
    private var done = false
    private var baseline = 0f

    /** Two fingers are down and still a candidate; the caller may keep other handlers from seeing the movement. */
    val tracking: Boolean get() = fingers.size == 2 && !failed

    /** A finger touched down. */
    fun down(id: Long, x: Float, y: Float) {
        if (id in fingers) return
        fingers[id] = Finger(x, y)
        when (fingers.size) {
            1 -> {
                failed = false
                done = false
            }
            2 -> {
                val (first, second) = fingers.values.toList()
                // The first finger was already doing something of its own (scrolling).
                if (hypot(first.x - first.startX, first.y - first.startY) > slop) failed = true
                fingers.values.forEach {
                    it.baseX = it.x
                    it.baseY = it.y
                }
                baseline = hypot(second.x - first.x, second.y - first.y)
            }
            else -> failed = true
        }
    }

    /** A finger moved; returns the gesture this movement completed, at most once per touch. */
    fun move(id: Long, x: Float, y: Float): TwoFingerGesture? {
        val finger = fingers[id] ?: return null
        finger.x = x
        finger.y = y
        if (fingers.size != 2 || failed || done) return null
        val (a, b) = fingers.values.toList()
        val ay = a.y - a.baseY
        val by = b.y - b.baseY
        val cx = (a.x - a.baseX + b.x - b.baseX) / 2
        val cy = (ay + by) / 2
        val spread = hypot(b.x - a.x, b.y - a.y) - baseline
        val travel = hypot(cx, cy)

        // Both fingers going the same way, mostly vertically, without the pair opening or closing much.
        val sameWay = ay * by > 0 && minOf(abs(ay), abs(by)) >= swipeDistance / 3
        if (sameWay && abs(cy) >= swipeDistance && abs(cx) <= abs(cy) / 2 && abs(spread) <= abs(cy) / 2) {
            return finish(if (cy > 0) TwoFingerGesture.SWIPE_DOWN else TwoFingerGesture.SWIPE_UP)
        }
        if (spread <= -pinchDistance && travel <= -spread / 2) return finish(TwoFingerGesture.PINCH_IN)
        // Clearly something else: sideways, or the fingers opening.
        if ((abs(cx) >= swipeDistance && abs(cx) > abs(cy)) || spread >= pinchDistance) failed = true
        return null
    }

    /** A finger lifted or was cancelled; with no fingers left the next touch starts afresh. */
    fun up(id: Long) {
        if (fingers.remove(id) == null) return
        // Lifting one of two before a match ends the attempt.
        if (fingers.size == 1 && !done) failed = true
        if (fingers.isEmpty()) {
            failed = false
            done = false
        }
    }

    /** Drops everything, e.g. when the gesture is switched off or the system cancelled the touch. */
    fun cancel() {
        fingers.clear()
        failed = false
        done = false
    }

    private fun finish(gesture: TwoFingerGesture): TwoFingerGesture {
        done = true
        return gesture
    }
}
