package io.github.danielphingston.metro.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.components.applySystemBars
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroColors
import io.github.danielphingston.metro.ui.theme.MetroTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()

    /** Bumped on every resume so the bar icons follow a wallpaper that changed meanwhile. */
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followOrientation(resources.configuration)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            MetroTheme(settings) {
                val colors = Metro.colors
                val wallpaper = settings.background == StartBackground.SYSTEM_WALLPAPER
                val resumed = resumes
                LaunchedEffect(colors, wallpaper, resumed) { applyWindowColors(colors, wallpaper) }
                LauncherScreen(viewModel)
            }
        }
    }

    /**
     * The window is opaque, which is the cheapest to draw, except in wallpaper mode: then it is
     * transparent and asks the system to composite the wallpaper behind it. The status and
     * navigation bar icons follow the page colour, or in wallpaper mode the wallpaper's own
     * brightness, since the theme says nothing about what is behind the bars.
     */
    private fun applyWindowColors(colors: MetroColors, wallpaper: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        applySystemBars(darkIcons = if (wallpaper) wallpaperSupportsDarkIcons() else !colors.isDark)
        window.setBackgroundDrawable(ColorDrawable(if (wallpaper) transparent else colors.background.toArgb()))
        // Make sure the surface itself is not opaque, so the wallpaper behind it can show.
        window.setFormat(if (wallpaper) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE)
        if (wallpaper) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    // Rotating, folding and attaching a keyboard are handled in place (see the manifest): Compose
    // lays out again for the new size, and the start screen keeps its scroll position.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        followOrientation(newConfig)
    }

    /**
     * Phones stay in portrait, like Windows Phone. Large screens (tablets, unfolded foldables)
     * turn freely: Android 16 ignores orientation locks on them anyway, so the lock is set here
     * rather than in the manifest, where Google Play flags it.
     */
    private fun followOrientation(config: Configuration) {
        val orientation = if (config.smallestScreenWidthDp >= LARGE_SCREEN_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    /** Whether the system wallpaper is light enough for dark status bar icons; no permission needed. */
    private fun wallpaperSupportsDarkIcons(): Boolean {
        val colors = getSystemService(WallpaperManager::class.java)?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        return colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // Home pressed while already on the launcher: animate back to the top of start.
            // Coming back from another app: reset silently.
            viewModel.onHomePressed(animate = hasWindowFocus())
        }
    }

    /**
     * Fallback for Home not arriving as a new intent (seen with Samsung's gesture navigation, where
     * the gesture can act on the task without re-delivering HOME), which would leave search open.
     * Leaving the launcher is the signal instead: the activity stopping, or losing the top-resumed
     * state and getting it back ([TopResumeTracker]). Neither fires for the keyboard (a separate
     * window) or for the shade and system dialogs drawn over the launcher; a translucent activity
     * on top (a permission dialog) only costs the top-resumed state, so it closes search too, which
     * is acceptable since search itself never starts one. Split-screen focus switches close it
     * as well, the price of not needing to know why the launcher lost focus.
     */
    override fun onStop() {
        super.onStop()
        viewModel.onLauncherLeft()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (topResume.update(isTopResumedActivity)) viewModel.onLauncherLeft()
    }

    private val topResume = TopResumeTracker()

    override fun onResume() {
        super.onResume()
        resumes++
        viewModel.refreshPermissions()
        NotificationAccess.rebindIfNeeded(this)
    }
}

/** Android's threshold for a large screen (tablets, unfolded foldables): the shorter side is at least this many dp. */
internal const val LARGE_SCREEN_DP = 600
