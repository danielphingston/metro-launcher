package io.github.danielphingston.metro

import androidx.compose.ui.geometry.Rect
import io.github.danielphingston.metro.data.LaunchStyle
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.ui.components.Motion
import io.github.danielphingston.metro.ui.components.Tilt
import io.github.danielphingston.metro.ui.start.AppWindow
import io.github.danielphingston.metro.ui.start.LaunchGuard
import io.github.danielphingston.metro.ui.start.Turnstile
import io.github.danielphingston.metro.ui.start.TurnstilePhase
import io.github.danielphingston.metro.ui.start.TurnstileState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchAnimationTest {
    private val unit = 100f
    private fun spec(style: LaunchStyle) = Turnstile.spec(style)
    private fun delay(style: LaunchStyle, column: Int, row: Int, tapped: Boolean = false) =
        spec(style).startDelay(column * unit, row * unit, unit, tapped)

    @Test
    fun turnstileIsTheDiagonalOrder() {
        assertEquals(Turnstile.staggerDelay(100f, 200f, unit, false), delay(LaunchStyle.TURNSTILE, 1, 2))
        assertTrue(delay(LaunchStyle.TURNSTILE, 2, 0) > delay(LaunchStyle.TURNSTILE, 1, 0))
    }

    @Test
    fun rowsGoTogetherTopToBottom() {
        val style = LaunchStyle.TURNSTILE_ROWS
        assertEquals(delay(style, 0, 2), delay(style, 3, 2))
        assertTrue(delay(style, 0, 2) > delay(style, 0, 0))
        assertTrue(delay(style, 0, 4) > delay(style, 0, 2))
    }

    @Test
    fun slideIsStaggeredByRow() {
        assertEquals(delay(LaunchStyle.SLIDE, 0, 3), delay(LaunchStyle.SLIDE, 3, 3))
        assertTrue(delay(LaunchStyle.SLIDE, 0, 3) > delay(LaunchStyle.SLIDE, 0, 0))
    }

    @Test
    fun zoomAndFadeMoveTogether() {
        for (style in listOf(LaunchStyle.ZOOM, LaunchStyle.FADE)) {
            assertEquals(0, delay(style, 0, 0))
            assertEquals(0, delay(style, 3, 9))
        }
    }

    @Test
    fun theTappedElementIsLastInEveryStyleThatStaggers() {
        for (style in LaunchStyle.entries) {
            val s = spec(style)
            val tappedDelay = delay(style, 0, 0, tapped = true)
            assertEquals(s.tappedDelayMs, tappedDelay)
            assertTrue(style.name, tappedDelay >= delay(style, 3, 40))
            assertEquals(s.tappedDelayMs + s.swingMs, s.totalMs)
        }
        assertTrue(delay(LaunchStyle.ZOOM, 0, 0, tapped = true) > delay(LaunchStyle.ZOOM, 2, 2))
        assertTrue(delay(LaunchStyle.TURNSTILE_ROWS, 0, 0, tapped = true) > delay(LaunchStyle.TURNSTILE_ROWS, 3, 40))
    }

    @Test
    fun staggersNeverExceedTheCap() {
        for (style in LaunchStyle.entries) assertTrue(delay(style, 7, 400) <= spec(style).maxStaggerMs.coerceAtLeast(0))
    }

    @Test
    fun turnstileTransformAtTheEnds() {
        val s = spec(LaunchStyle.TURNSTILE)
        assertEquals(0f, s.poseAt(0f, true).rotationY, 1e-4f)
        assertEquals(0.5f * 0 + Turnstile.MAX_DEGREES, s.poseAt(1f, true).rotationY, 1e-4f)
        assertEquals(-Turnstile.MAX_DEGREES, s.poseAt(0f, false).rotationY, 1e-4f)
        assertEquals(0f, s.poseAt(1f, false).rotationY, 1e-4f)
        assertEquals(0f, s.poseAt(1f, true).alpha, 1e-4f)
        assertEquals(1f, s.poseAt(1f, false).alpha, 1e-4f)
        val mid = s.poseAt(0.5f, true)
        assertTrue(mid.rotationY in 1f..Turnstile.MAX_DEGREES - 1f)
        assertTrue(mid.alpha in 0.1f..0.99f)
    }

    @Test
    fun slideMovesDownAndFades() {
        val s = spec(LaunchStyle.SLIDE)
        assertEquals(0f, s.poseAt(0f, true).translationDp, 1e-4f)
        assertEquals(Turnstile.SLIDE_DP, s.poseAt(1f, true).translationDp, 1e-4f)
        assertEquals(Turnstile.SLIDE_DP, s.poseAt(0f, false).translationDp, 1e-4f)
        assertEquals(0f, s.poseAt(1f, false).translationDp, 1e-4f)
        assertEquals(0f, s.poseAt(1f, true).rotationY, 0f)
        assertTrue(s.poseAt(0.5f, true).alpha < 1f)
    }

    @Test
    fun zoomShrinksTowardTheTappedElement() {
        val s = spec(LaunchStyle.ZOOM)
        assertEquals(1f, s.poseAt(0f, true).scale, 1e-4f)
        assertEquals(Turnstile.ZOOM_SCALE, s.poseAt(1f, true).scale, 1e-4f)
        assertEquals(Turnstile.ZOOM_SCALE, s.poseAt(0f, false).scale, 1e-4f)
        assertEquals(1f, s.poseAt(1f, false).scale, 1e-4f)
        // The tapped tile's centre is (150, 250); an element at (0, 100) pivots at that point in its own pixels.
        val pivot = s.pivot(Rect(0f, 100f, 100f, 200f), Rect(100f, 200f, 200f, 300f), 400f, 800f)
        assertEquals(150f, pivot.x, 1e-4f)
        assertEquals(150f, pivot.y, 1e-4f)
        val centred = s.pivot(Rect(0f, 100f, 100f, 200f), null, 400f, 800f)
        assertEquals(200f, centred.x, 1e-4f)
    }

    @Test
    fun fadeOnlyChangesAlpha() {
        val pose = spec(LaunchStyle.FADE).poseAt(0.5f, true)
        assertEquals(0f, pose.rotationY, 0f)
        assertEquals(0f, pose.translationDp, 0f)
        assertEquals(1f, pose.scale, 0f)
        assertTrue(pose.alpha < 1f)
        assertEquals(0f, spec(LaunchStyle.FADE).poseAt(1f, true).alpha, 1e-4f)
    }

    @Test
    fun turnstilePivotIsTheScreenEdge() {
        val pivot = spec(LaunchStyle.TURNSTILE).pivot(Rect(120f, 300f, 220f, 400f), null, 400f, 800f)
        assertEquals(-120f, pivot.x, 1e-4f)
        assertEquals(100f, pivot.y, 1e-4f)
    }

    @Test
    fun returningStartsWithWhatLeftLast() {
        val s = spec(LaunchStyle.TURNSTILE)
        assertEquals(0, s.returnStart(s.tappedDelayMs))
        assertEquals(s.tappedDelayMs, s.returnStart(0))
    }

    @Test
    fun appWindowPerStyle() {
        assertEquals(AppWindow.FADE, spec(LaunchStyle.TURNSTILE).appWindow)
        assertEquals(AppWindow.FADE, spec(LaunchStyle.TURNSTILE_ROWS).appWindow)
        assertEquals(AppWindow.FADE, spec(LaunchStyle.FADE).appWindow)
        assertEquals(AppWindow.SLIDE_UP, spec(LaunchStyle.SLIDE).appWindow)
        assertEquals(AppWindow.ZOOM_IN, spec(LaunchStyle.ZOOM).appWindow)
        assertEquals(AppWindow.CLIP_REVEAL, spec(LaunchStyle.NONE).appWindow)
    }

    @Test
    fun tappedIsMatchedLooselyByCentre() {
        val tapped = Rect(100f, 100f, 200f, 200f)
        assertTrue(Turnstile.isTapped(Rect(102f, 98f, 198f, 202f), tapped))
        assertFalse(Turnstile.isTapped(Rect(204f, 100f, 304f, 200f), tapped))
        assertFalse(Turnstile.isTapped(tapped, null))
    }

    @Test
    fun speedAndSystemScale() {
        assertEquals(1f, Turnstile.timeScale(1f, 1f), 0f)
        assertEquals(0.5f, Turnstile.timeScale(2f, 1f), 0f)
        assertEquals(2f, Turnstile.timeScale(0.5f, 1f), 0f)
        assertEquals(1.5f, Turnstile.timeScale(1f, 1.5f), 0f)
        assertEquals(0.75f, Turnstile.timeScale(2f, 1.5f), 0f)
        assertEquals(Turnstile.EXIT_TIMEOUT_MS, Turnstile.exitTimeoutMs(0.5f))
        assertEquals(Turnstile.EXIT_TIMEOUT_MS * 2, Turnstile.exitTimeoutMs(2f))
        assertEquals(2, Motion(speed = 2f).duration(4))
        assertEquals(1_300, Motion(speed = 0.5f).duration(650))
    }

    @Test
    fun speedLabels() {
        assertEquals("1x", Motion.speedLabel(100))
        assertEquals("1.25x", Motion.speedLabel(125))
        assertEquals("0.5x", Motion.speedLabel(50))
        assertEquals("2x", Motion.speedLabel(200))
        assertEquals("2x", Motion.speedLabel(900))
    }

    @Test
    fun openEarlyIsPartWayThroughTheExit() {
        for (style in LaunchStyle.entries) {
            val s = spec(style)
            val at = Turnstile.openAtMs(s)
            assertEquals(s.totalMs * 0.55f, at, 1e-3f)
            assertTrue(at < s.totalMs)
        }
        assertEquals(0.55f, Turnstile.OPEN_EARLY_FRACTION, 0f)
    }

    @Test
    fun returnWaitsLongerForSlowColdStarts() = assertTrue(Turnstile.HOLD_TIMEOUT_MS >= 2_500)

    @Test
    fun doubleLaunchIsIgnoredWithinAWindow() {
        val guard = LaunchGuard()
        assertTrue(guard.tryLaunch(10_000))
        assertFalse(guard.tryLaunch(10_300))
        assertFalse(guard.tryLaunch(10_599))
        assertTrue(guard.tryLaunch(10_600))
        assertFalse(guard.tryLaunch(10_700))
        // The ignored tap did not extend the window.
        assertTrue(guard.tryLaunch(11_200))
    }

    @Test
    fun launchGuardAcceptsTheFirstLaunchAtTimeZero() = assertTrue(LaunchGuard().tryLaunch(0))

    @Test
    fun noneNeverRunsAnExit() = runBlocking {
        val state = TurnstileState()
        assertFalse(state.exit(null, LaunchStyle.NONE))
        assertFalse(state.busy)
    }

    @Test
    fun seekKeepsTheStyle() {
        val state = TurnstileState()
        state.seek(TurnstilePhase.OUT, 50f, null, LaunchStyle.ZOOM)
        assertTrue(state.busy)
        assertEquals(spec(LaunchStyle.ZOOM).totalMs, state.spec.totalMs)
        assertNotEquals(spec(LaunchStyle.TURNSTILE).totalMs, state.spec.totalMs)
    }

    @Test
    fun flipDelayJitterIsPlusMinusForty() {
        assertEquals(2_400, Motion.flipDelayMs(4_000, 0f))
        assertEquals(4_000, Motion.flipDelayMs(4_000, 0.5f))
        assertEquals(5_600, Motion.flipDelayMs(4_000, 1f))
    }

    @Test
    fun motionComesFromSettingsClamped() {
        val m = Motion.of(LauncherSettings(animationSpeed = 999, tiltDegrees = 1, flipIntervalSeconds = 99, liveTileFlips = false))
        assertEquals(2f, m.speed, 0f)
        assertEquals(3f, m.tiltDegrees, 0f)
        assertEquals(15_000L, m.flipIntervalMs)
        assertFalse(m.flipsEnabled)
        assertEquals(1f, Motion.of(LauncherSettings()).speed, 0f)
    }

    @Test
    fun tiltScalesWithTheSetting() {
        assertEquals(9f, Tilt.degrees(9f, 9f), 0f)
        assertEquals(6f, Tilt.degrees(6f, 9f), 0f)
        assertEquals(10f, Tilt.degrees(6f, 15f), 1e-4f)
        assertEquals(9f, Tilt.rotationY(1f, 9f), 0f)
        assertEquals(-9f, Tilt.rotationX(1f, 9f), 0f)
        assertEquals(0.96f, Tilt.scale(0f, 0f), 1e-6f)
        assertEquals(1f, Tilt.scale(1f, 0f), 1e-6f)
    }

    @Test
    fun clampsForEveryNewSetting() {
        assertEquals(50, LauncherSettings.clampAnimationSpeed(0))
        assertEquals(200, LauncherSettings.clampAnimationSpeed(10_000))
        assertEquals(125, LauncherSettings.clampAnimationSpeed(125))
        assertEquals(3, LauncherSettings.clampTiltDegrees(-5))
        assertEquals(15, LauncherSettings.clampTiltDegrees(40))
        assertEquals(9, LauncherSettings.clampTiltDegrees(9))
        assertEquals(2, LauncherSettings.clampFlipInterval(0))
        assertEquals(15, LauncherSettings.clampFlipInterval(60))
        assertEquals(4, LauncherSettings.clampFlipInterval(4))
    }

    @Test
    fun defaults() {
        val d = LauncherSettings()
        assertEquals(LaunchStyle.TURNSTILE, d.launchStyle)
        assertEquals(100, d.animationSpeed)
        assertTrue(d.animateReturn && d.openDuringAnimation && d.tiltEnabled && d.liveTileFlips && d.swingPages)
        assertEquals(9, d.tiltDegrees)
        assertEquals(4, d.flipIntervalSeconds)
    }
}
