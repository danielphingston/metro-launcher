package io.github.danielphingston.metro

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.Gesture
import io.github.danielphingston.metro.data.GestureAction
import io.github.danielphingston.metro.data.GestureBinding
import io.github.danielphingston.metro.data.GestureSettings
import io.github.danielphingston.metro.ui.gestures.DoubleTapRecognizer
import io.github.danielphingston.metro.ui.gestures.GestureRoute
import io.github.danielphingston.metro.ui.gestures.GestureRouting
import io.github.danielphingston.metro.ui.gestures.TwoFingerGesture
import io.github.danielphingston.metro.ui.gestures.TwoFingerRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    // --- actions and settings ---

    @Test
    fun actionIdsRoundTripAndAreUnique() {
        assertEquals(GestureAction.entries.size, GestureAction.entries.map { it.id }.toSet().size)
        GestureAction.entries.forEach { assertEquals(it, GestureAction.fromId(it.id)) }
    }

    @Test
    fun storedIdsStayStable() {
        assertEquals(
            listOf("none", "sleep", "notifications", "quick_settings", "search", "app_list", "edit_tiles", "metro_settings", "recents", "open_app"),
            GestureAction.entries.map { it.id },
        )
        assertEquals(
            listOf("gesture_double_tap", "gesture_two_finger_down", "gesture_two_finger_up", "gesture_pinch_in"),
            Gesture.entries.map { it.actionKey.name },
        )
        assertEquals("gesture_pinch_in_app", Gesture.PINCH_IN.appKey.name)
    }

    @Test
    fun everyGestureDefaultsToNone() {
        val settings = GestureSettings.from(mutablePreferencesOf())
        Gesture.entries.forEach {
            assertEquals(GestureAction.NONE, settings[it].action)
            assertFalse(settings[it].active)
        }
        assertEquals(GestureAction.NONE, GestureSettings()[Gesture.DOUBLE_TAP].action)
    }

    @Test
    fun unknownIdsDecodeToNone() {
        val prefs = mutablePreferencesOf(
            Gesture.DOUBLE_TAP.actionKey to "teleport",
            Gesture.PINCH_IN.actionKey to "search",
        )
        val settings = GestureSettings.from(prefs)
        assertEquals(GestureAction.NONE, settings[Gesture.DOUBLE_TAP].action)
        assertEquals(GestureAction.SEARCH, settings[Gesture.PINCH_IN].action)
        assertEquals(GestureAction.NONE, GestureAction.fromId(null))
    }

    @Test
    fun openAppNeedsAUsableApp() {
        val app = AppKey("com.example", "com.example.Main", 0)
        val prefs = mutablePreferencesOf(
            Gesture.DOUBLE_TAP.actionKey to "open_app",
            Gesture.DOUBLE_TAP.appKey to app.serialize(),
            Gesture.PINCH_IN.actionKey to "open_app",
            Gesture.PINCH_IN.appKey to "garbage",
        )
        val settings = GestureSettings.from(prefs)
        assertEquals(app, settings[Gesture.DOUBLE_TAP].app)
        assertTrue(settings[Gesture.DOUBLE_TAP].active)
        assertNull(settings[Gesture.PINCH_IN].app)
        assertFalse(settings[Gesture.PINCH_IN].active)
    }

    @Test
    fun accessibilityNeeds() {
        assertEquals(
            setOf(GestureAction.SLEEP, GestureAction.RECENTS, GestureAction.NOTIFICATIONS, GestureAction.QUICK_SETTINGS),
            GestureAction.entries.filter { it.usesAccessibility }.toSet(),
        )
        assertEquals(GestureAction.Access.REQUIRED, GestureAction.SLEEP.access)
        assertEquals(GestureAction.Access.FALLBACK, GestureAction.NOTIFICATIONS.access)
    }

    @Test
    fun routingWithoutTheServiceOnlyBlocksWhatRequiresIt() {
        fun route(action: GestureAction, on: Boolean, app: AppKey? = null) = GestureRouting.route(GestureBinding(action, app), on)
        assertEquals(GestureRoute.NOTHING, route(GestureAction.NONE, true))
        assertEquals(GestureRoute.NEEDS_SERVICE, route(GestureAction.SLEEP, false))
        assertEquals(GestureRoute.NEEDS_SERVICE, route(GestureAction.RECENTS, false))
        assertEquals(GestureRoute.PERFORM, route(GestureAction.SLEEP, true))
        // The shade tries the direct way first, so it is not blocked up front.
        assertEquals(GestureRoute.PERFORM, route(GestureAction.NOTIFICATIONS, false))
        assertEquals(GestureRoute.PERFORM, route(GestureAction.SEARCH, false))
        assertEquals(GestureRoute.NOTHING, route(GestureAction.OPEN_APP, true))
        assertEquals(GestureRoute.PERFORM, route(GestureAction.OPEN_APP, false, AppKey("a", "b", 0)))
    }

    // --- double tap ---

    private fun doubleTap() = DoubleTapRecognizer(slop = 100f)

    @Test
    fun twoQuickTapsAreADoubleTap() {
        val d = doubleTap()
        assertFalse(d.onTap(0, 50, 10f, 10f))
        assertTrue(d.onTap(200, 250, 20f, 15f))
    }

    @Test
    fun aThirdTapStartsOver() {
        val d = doubleTap()
        d.onTap(0, 50, 0f, 0f)
        assertTrue(d.onTap(100, 150, 0f, 0f))
        assertFalse(d.onTap(200, 250, 0f, 0f))
    }

    @Test
    fun tooSlowOrTooFarApartIsNotADoubleTap() {
        val slow = doubleTap()
        slow.onTap(0, 50, 0f, 0f)
        assertFalse(slow.onTap(50 + DoubleTapRecognizer.TIMEOUT_MS + 1, 500, 0f, 0f))
        val far = doubleTap()
        far.onTap(0, 50, 0f, 0f)
        assertFalse(far.onTap(100, 150, 300f, 0f))
    }

    @Test
    fun anIneligibleTapBreaksThePair() {
        val d = doubleTap()
        d.onTap(0, 50, 0f, 0f)
        assertFalse(d.onTap(100, 150, 0f, 0f, eligible = false))
        assertFalse(d.onTap(200, 250, 0f, 0f))
        // The tap that follows an ineligible one is a first tap again.
        assertTrue(d.onTap(300, 350, 0f, 0f))
    }

    @Test
    fun resetForgetsTheFirstTap() {
        val d = doubleTap()
        d.onTap(0, 50, 0f, 0f)
        d.reset()
        assertFalse(d.onTap(100, 150, 0f, 0f))
    }

    // --- two fingers ---

    private val swipe = 100f
    private val pinch = 80f

    private fun recognizer() = TwoFingerRecognizer(swipeDistance = swipe, pinchDistance = pinch, slop = 20f)

    /** Two fingers down side by side; moves both by (dx1, dy1) and (dx2, dy2) in steps and returns what matched. */
    private fun drive(
        r: TwoFingerRecognizer,
        d1: Pair<Float, Float>,
        d2: Pair<Float, Float>,
        steps: Int = 10,
        lift: Boolean = true,
    ): List<TwoFingerGesture> {
        val found = mutableListOf<TwoFingerGesture>()
        r.down(1, 200f, 500f)
        r.down(2, 400f, 500f)
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            r.move(1, 200f + d1.first * f, 500f + d1.second * f)?.let(found::add)
            r.move(2, 400f + d2.first * f, 500f + d2.second * f)?.let(found::add)
        }
        if (lift) {
            r.up(1)
            r.up(2)
        }
        return found
    }

    @Test
    fun twoFingersDownIsASwipeDown() {
        assertEquals(listOf(TwoFingerGesture.SWIPE_DOWN), drive(recognizer(), 0f to 150f, 5f to 140f))
    }

    @Test
    fun twoFingersUpIsASwipeUp() {
        assertEquals(listOf(TwoFingerGesture.SWIPE_UP), drive(recognizer(), 0f to -150f, 0f to -150f))
    }

    @Test
    fun fingersClosingInIsAPinch() {
        assertEquals(listOf(TwoFingerGesture.PINCH_IN), drive(recognizer(), 60f to 0f, -60f to 0f))
    }

    @Test
    fun aGestureIsReportedOnlyOncePerTouch() {
        val r = recognizer()
        assertEquals(1, drive(r, 0f to 400f, 0f to 400f, lift = false).size)
    }

    @Test
    fun shortOrWrongMovementsAreNothing() {
        assertTrue(drive(recognizer(), 0f to 40f, 0f to 40f).isEmpty())
        // Spreading apart is not a pinch in.
        assertTrue(drive(recognizer(), -80f to 0f, 80f to 0f).isEmpty())
        // Sideways.
        assertTrue(drive(recognizer(), 150f to 10f, 150f to 10f).isEmpty())
        // One finger moving, the other still: not a swipe (and not enough closing for a pinch).
        assertTrue(drive(recognizer(), 0f to 150f, 0f to 0f).isEmpty())
        // Opposite vertical directions is a twist, not a swipe.
        assertTrue(drive(recognizer(), 0f to 150f, 0f to -150f).isEmpty())
    }

    @Test
    fun aSwipeThatAlsoPinchesTooMuchIsNotASwipe() {
        // Moves down 100 but the fingers close by 100 too.
        assertTrue(drive(recognizer(), 50f to 100f, -50f to 100f).none { it != TwoFingerGesture.PINCH_IN })
    }

    @Test
    fun aSingleFingerNeverMatches() {
        val r = recognizer()
        r.down(1, 200f, 500f)
        for (i in 1..30) assertNull(r.move(1, 200f, 500f + i * 20f))
        r.up(1)
        assertFalse(r.tracking)
    }

    @Test
    fun aThirdFingerFailsTheGestureUntilAllAreUp() {
        val r = recognizer()
        r.down(1, 200f, 500f)
        r.down(2, 400f, 500f)
        r.down(3, 300f, 600f)
        assertFalse(r.tracking)
        for (i in 1..10) {
            assertNull(r.move(1, 200f, 500f + i * 20f))
            assertNull(r.move(2, 400f, 500f + i * 20f))
        }
        r.up(3)
        assertNull(r.move(1, 200f, 900f))
        r.up(1)
        r.up(2)
        // A fresh touch works again.
        assertEquals(listOf(TwoFingerGesture.SWIPE_DOWN), drive(r, 0f to 150f, 0f to 150f))
    }

    @Test
    fun liftingAFingerEarlyCancels() {
        val r = recognizer()
        r.down(1, 200f, 500f)
        r.down(2, 400f, 500f)
        r.move(1, 200f, 540f)
        r.move(2, 400f, 540f)
        r.up(2)
        assertFalse(r.tracking)
        assertNull(r.move(1, 200f, 800f))
        // Putting a finger back down does not revive it.
        r.down(2, 400f, 800f)
        assertNull(r.move(2, 400f, 1000f))
        assertNull(r.move(1, 200f, 1000f))
    }

    @Test
    fun aScrollingFingerDoesNotTurnIntoATwoFingerSwipe() {
        val r = recognizer()
        r.down(1, 200f, 500f)
        r.move(1, 200f, 700f)
        r.down(2, 400f, 700f)
        assertFalse(r.tracking)
        for (i in 1..10) {
            assertNull(r.move(1, 200f, 700f + i * 30f))
            assertNull(r.move(2, 400f, 700f + i * 30f))
        }
    }

    @Test
    fun cancelDropsEverything() {
        val r = recognizer()
        r.down(1, 200f, 500f)
        r.down(2, 400f, 500f)
        assertTrue(r.tracking)
        r.cancel()
        assertFalse(r.tracking)
        assertEquals(listOf(TwoFingerGesture.SWIPE_UP), drive(r, 0f to -150f, 0f to -150f))
    }

    @Test
    fun recognisingKeepsTrackingSoTheCallerKeepsConsuming() {
        val r = recognizer()
        drive(r, 0f to 150f, 0f to 150f, lift = false)
        assertTrue(r.tracking)
    }
}
