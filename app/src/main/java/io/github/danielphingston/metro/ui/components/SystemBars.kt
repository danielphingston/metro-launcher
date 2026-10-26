package io.github.danielphingston.metro.ui.components

import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat

/**
 * Lays the window out behind transparent status and navigation bars, with dark bar icons when
 * [darkIcons] is set. From Android 15 every window is edge to edge with transparent bars; before
 * it the theme makes the bars transparent and this lets the content draw behind them.
 *
 * Unlike androidx's enableEdgeToEdge this never sets bar colours or the cutout mode, which
 * Android 15 deprecates and Google Play flags.
 */
fun Activity.applySystemBars(darkIcons: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = darkIcons
        isAppearanceLightNavigationBars = darkIcons
    }
}
