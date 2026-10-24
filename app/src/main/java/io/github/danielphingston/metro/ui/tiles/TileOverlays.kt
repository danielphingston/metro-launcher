package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** The tile label at the bottom left over a subtle dark gradient, so it reads on any photo. */
@Composable
internal fun BoxScope.ScrimmedLabel(text: String, m: TileMetrics) {
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(m.dp(34f))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))),
    )
    TileLabel(text, m, Modifier.align(Alignment.BottomStart))
}

/** The accent tile shown while a photo or people tile has nothing to show, with a hint. */
@Composable
internal fun HintFace(label: String, hint: String, size: TileSize, m: TileMetrics) {
    Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
        if (size != TileSize.SMALL) {
            MetroText(
                hint,
                MetroType.style(m.text(14f)),
                color = Metro.colors.onAccent,
                modifier = Modifier.padding(start = m.dp(8f), top = m.dp(6f), end = m.dp(8f)),
            )
        }
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }
}
