package io.github.danielphingston.metro.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.ui.theme.Metro

/** Black at this alpha over a photo or the wallpaper, where the page colour isn't what is behind the status bar. */
private const val PHOTO_SCRIM_ALPHA = 0.7f

/**
 * A fade at the top of the screen, over the status bar and 8dp below it, so content that scrolls
 * up under the clock and icons doesn't clash with them. It is the page colour, or black
 * ([PHOTO_SCRIM_ALPHA]) when [overPhoto]. A gradient fill and nothing else (no blur, no touch
 * handling), so it costs one rectangle per frame and touches go through to what is below it.
 * Compose it after the content it covers.
 */
@Composable
fun StatusBarScrim(overPhoto: Boolean, modifier: Modifier = Modifier) {
    val top = if (overPhoto) Color.Black.copy(alpha = PHOTO_SCRIM_ALPHA) else Metro.colors.background
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Brush.verticalGradient(listOf(top, top.copy(alpha = 0f)))) },
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Spacer(Modifier.height(8.dp))
    }
}
