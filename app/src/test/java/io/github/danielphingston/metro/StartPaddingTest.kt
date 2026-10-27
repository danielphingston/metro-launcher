package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.StartPadding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The settings live in a DataStore file, which needs a Context.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartPaddingTest {
    private val repository = SettingsRepository(RuntimeEnvironment.getApplication())

    private fun read() = runBlocking { repository.settings.first() }

    @Test
    fun defaultsAreTheOriginalValues() {
        // The store is shared between tests, so look at the declared defaults.
        val s = LauncherSettings()
        assertEquals(28, s.startTopPadding)
        assertEquals(16, s.startBottomPadding)
        assertEquals(8, s.startLeftPadding)
        assertEquals(8, s.startRightPadding)
        assertEquals(false, s.accentArrows)
    }

    @Test
    fun paddingsAreClampedOnWrite() = runBlocking {
        repository.setStartTopPadding(-10)
        repository.setStartBottomPadding(500)
        repository.setStartLeftPadding(500)
        repository.setStartRightPadding(-1)
        val s = read()
        assertEquals(LauncherSettings.MIN_START_TOP_PADDING, s.startTopPadding)
        assertEquals(LauncherSettings.MAX_START_BOTTOM_PADDING, s.startBottomPadding)
        assertEquals(LauncherSettings.MAX_START_SIDE_PADDING, s.startLeftPadding)
        assertEquals(LauncherSettings.MIN_START_SIDE_PADDING, s.startRightPadding)
    }

    @Test
    fun otherSettingsAreClampedOnWrite() = runBlocking {
        repository.setColumns(99)
        repository.setTileTransparency(400)
        repository.setPullDistance(1)
        repository.setScrubberPosition(-5)
        repository.setAccent(0x001BA1E2)
        val s = read()
        assertEquals(LauncherSettings.MAX_COLUMNS, s.columns)
        assertEquals(1f, s.transparencyFraction, 0f)
        assertEquals(LauncherSettings.MIN_PULL_DISTANCE, s.pullDistance)
        assertEquals(LauncherSettings.MIN_SCRUBBER_POSITION, s.scrubberPosition)
        assertEquals(0xFF1BA1E2.toInt(), s.accent)
    }

    @Test
    fun clampHelpers() {
        assertEquals(4, LauncherSettings.clampColumns(Int.MIN_VALUE))
        assertEquals(4, LauncherSettings.clampColumns(4))
        assertEquals(6, LauncherSettings.clampColumns(5))
        assertEquals(8, LauncherSettings.clampColumns(7))
        assertEquals(8, LauncherSettings.clampColumns(Int.MAX_VALUE))
        assertEquals(0, LauncherSettings.clampTransparency(-1))
        assertEquals(100, LauncherSettings.clampTransparency(101))
        assertEquals(200, LauncherSettings.clampPullDistance(Int.MAX_VALUE))
        assertEquals(40, LauncherSettings.clampPullDistance(Int.MIN_VALUE))
        assertEquals(40, LauncherSettings.clampScrubberPosition(41))
        assertEquals(96, LauncherSettings.clampStartTopPadding(97))
        assertEquals(0, LauncherSettings.clampStartBottomPadding(-1))
        assertEquals(48, LauncherSettings.clampStartSidePadding(49))
    }

    @Test
    fun fitKeepsPaddingsThatFit() {
        val s = LauncherSettings(startTopPadding = 60, startBottomPadding = 50, startLeftPadding = 20, startRightPadding = 30)
        val p = StartPadding.fit(s, availableWidthDp = 400f, availableHeightDp = 800f, tileGapDp = 4f)
        assertEquals(StartPadding(60f, 50f, 20f, 30f), p)
    }

    @Test
    fun fitShrinksSidesToKeepTheColumns() {
        val s = LauncherSettings(startLeftPadding = 48, startRightPadding = 48)
        val p = StartPadding.fit(s, availableWidthDp = 220f, availableHeightDp = 800f, tileGapDp = 4f)
        // 220 - 4 * 40 + 4 leaves 64 for both sides.
        assertEquals(64f, p.left + p.right, 0.001f)
        assertEquals(p.left, p.right, 0.001f)
    }

    @Test
    fun fitKeepsRoomToScrollOnShortScreens() {
        val s = LauncherSettings(startTopPadding = 96, startBottomPadding = 96)
        val p = StartPadding.fit(s, availableWidthDp = 800f, availableHeightDp = 300f, tileGapDp = 4f)
        assertEquals(100f, p.top + p.bottom, 0.001f)
    }

    @Test
    fun fitAlwaysAllowsTheDefaults() {
        val p = StartPadding.fit(LauncherSettings(), availableWidthDp = 100f, availableHeightDp = 100f, tileGapDp = 4f)
        assertEquals(StartPadding(28f, 16f, 8f, 8f), p)
        assertTrue(p.left >= 0f && p.top >= 0f)
    }
}
