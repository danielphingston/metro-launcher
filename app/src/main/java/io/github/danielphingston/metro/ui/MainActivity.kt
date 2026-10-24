package io.github.danielphingston.metro.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroColors
import io.github.danielphingston.metro.ui.theme.MetroTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            MetroTheme(settings) {
                val colors = Metro.colors
                val wallpaper = settings.background == StartBackground.SYSTEM_WALLPAPER
                LaunchedEffect(colors, wallpaper) { applyWindowColors(colors, wallpaper) }
                LauncherScreen(viewModel)
            }
        }
    }

    /**
     * The window is opaque, which is the cheapest to draw, except in wallpaper mode: then it is
     * transparent and asks the system to composite the wallpaper behind it.
     */
    private fun applyWindowColors(colors: MetroColors, wallpaper: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (colors.isDark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(if (wallpaper) transparent else colors.background.toArgb()))
        if (wallpaper) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // Home pressed while already on the launcher: animate back to the top of start.
            // Coming back from another app: reset silently.
            viewModel.onHomePressed(animate = hasWindowFocus())
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
        NotificationAccess.rebindIfNeeded(this)
    }
}
