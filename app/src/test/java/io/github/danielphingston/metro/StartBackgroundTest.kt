package io.github.danielphingston.metro

import android.content.ComponentName
import android.os.Process
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.ui.applist.AppGroups
import io.github.danielphingston.metro.ui.applist.AppListRow
import io.github.danielphingston.metro.ui.applist.appListRows
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// AppInfo holds Android types and BackgroundPhoto.centerCrop returns a Rect.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartBackgroundTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun app(label: String, installedDaysAgo: Double?, uninstallable: Boolean = true) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = ComponentName("com.example", "Main"),
        isUninstallable = uninstallable,
        installTime = installedDaysAgo?.let { now - (it * day).toLong() } ?: 0L,
        loadIcon = { _, _ -> throw UnsupportedOperationException() },
    )

    @Test
    fun recentlyAddedIsNewestFirstAndLimited() {
        val apps = listOf(
            app("A", 4.0), app("B", 0.1), app("C", 2.0), app("D", 1.0), app("E", 30.0), app("F", null),
        )
        assertEquals(listOf("B", "D", "C"), AppGroups.recentlyAdded(apps, now).map { it.label })
    }

    @Test
    fun recentlyAddedSkipsOldSystemAndFutureApps() {
        val apps = listOf(app("Old", 5.5), app("Preinstalled", 1.0, uninstallable = false), app("Future", -2.0))
        assertTrue(AppGroups.recentlyAdded(apps, now).isEmpty())
    }

    @Test
    fun rowsListRecentAppsFirstThenLetters() {
        val rows = appListRows(listOf(app("Alpha", 40.0), app("Beta", 1.0)), "", now)
        assertEquals(
            listOf(
                AppListRow.RecentHeader.key, "r:com.example.beta/Main#0",
                "h:a", "a:com.example.alpha/Main#0", "h:b", "a:com.example.beta/Main#0",
            ),
            rows.map { it.key },
        )
    }

    @Test
    fun searchHidesGroupsAndRecents() {
        val rows = appListRows(listOf(app("Alpha", 40.0), app("Beta", 1.0)), "bet", now)
        assertEquals(listOf("a:com.example.beta/Main#0"), rows.map { it.key })
    }

    @Test
    fun transparencyDefaultsDependOnTheMode() {
        assertEquals(0f, LauncherSettings().transparencyFraction, 0f)
        assertEquals(0.45f, LauncherSettings(background = StartBackground.PHOTO_FULL).transparencyFraction, 0f)
        assertEquals(0.2f, LauncherSettings(background = StartBackground.PHOTO_FULL, tileTransparency = 20).transparencyFraction, 0f)
    }

    @Test
    fun accentFromBackgroundNeedsAPhotoMode() {
        val chosen = 0xFF1BA1E2.toInt()
        val derived = 0xFFAA2244.toInt()
        val base = LauncherSettings(accent = chosen, backgroundAccent = derived, accentFromBackground = true)
        assertEquals(chosen, base.effectiveAccent)
        assertEquals(derived, base.copy(background = StartBackground.PHOTO_TILES).effectiveAccent)
        assertEquals(chosen, base.copy(background = StartBackground.PHOTO_FULL, backgroundAccent = null).effectiveAccent)
        assertEquals(chosen, base.copy(background = StartBackground.PHOTO_FULL, accentFromBackground = false).effectiveAccent)
    }

    @Test
    fun photoModesStaySolidUntilTheImageLoads() {
        val settings = LauncherSettings(background = StartBackground.PHOTO_FULL)
        val backdrop = StartBackdrop.of(settings, null) { 0f }
        assertEquals(StartBackground.SOLID, backdrop.mode)
        assertEquals(1f, backdrop.tileAlpha, 0f)
    }

    @Test
    fun tileAlphaFollowsTransparency() {
        fun alpha(mode: StartBackground, percent: Int) =
            StartBackdrop(mode, null, percent / 100f) { 0f }.tileAlpha
        assertEquals(0.55f, alpha(StartBackground.PHOTO_FULL, 45), 0.001f)
        assertEquals(1f, alpha(StartBackground.SOLID, 0), 0f)
        // SOLID only blends lightly: full transparency keeps 40% of the accent.
        assertEquals(0.4f, alpha(StartBackground.SOLID, 100), 0.001f)
    }

    @Test
    fun parallaxIsLimitedToTheHeadroom() {
        val backdrop = StartBackdrop(StartBackground.PHOTO_FULL, null, 0.5f) { 1_000_000f }
        assertEquals(2000f * BackgroundPhoto.HEADROOM, backdrop.offsetFor(2000f), 0.001f)
        assertEquals(0f, StartBackdrop(StartBackground.PHOTO_FULL, null, 0f) { -5f }.offsetFor(2000f), 0f)
        assertEquals(30f, StartBackdrop(StartBackground.PHOTO_FULL, null, 0f) { 30f }.offsetFor(2000f), 0f)
    }

    @Test
    fun centerCropKeepsTheBoxAspect() {
        // A wide photo is cropped at the sides, a tall one at top and bottom.
        val wide = BackgroundPhoto.centerCrop(3000, 1000, 1000, 2000)
        assertEquals(500, wide.width())
        assertEquals(1000, wide.height())
        assertEquals(1250, wide.left)
        val tall = BackgroundPhoto.centerCrop(1000, 4000, 1000, 2000)
        assertEquals(1000, tall.width())
        assertEquals(2000, tall.height())
        assertEquals(1000, tall.top)
        assertFalse(wide.isEmpty)
    }
}
