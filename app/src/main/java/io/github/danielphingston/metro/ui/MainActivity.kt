package io.github.danielphingston.metro.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                LaunchedEffect(colors) { applyWindowColors(colors) }
                LauncherScreen(viewModel)
            }
        }
    }

    private fun applyWindowColors(colors: MetroColors) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (colors.isDark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
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
