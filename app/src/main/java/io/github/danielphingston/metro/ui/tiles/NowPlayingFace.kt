package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.NowPlaying
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** Album art filling the tile; without art, the song title on the accent colour. */
@Composable
internal fun NowPlayingFront(app: AppInfo, size: TileSize, info: NowPlaying, m: TileMetrics) {
    val art = info.art
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (art == null) {
            MetroText(
                info.title,
                MetroType.style(m.text(if (size == TileSize.SMALL) 13f else 17f), FontWeight.SemiBold),
                color = Metro.colors.onAccent,
                maxLines = 3,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = m.dp(6f)),
            )
        } else {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (size != TileSize.SMALL) {
            if (art != null) {
                // Keeps the white label legible over bright art.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(m.dp(44f))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
                )
            }
            TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
internal fun NowPlayingBack(app: AppInfo, size: TileSize, info: NowPlaying, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = m.dp(8f), top = m.dp(6f), end = m.dp(8f))) {
            MetroText(if (info.isPlaying) "now playing" else "paused", MetroType.style(m.text(15f)), color = white, maxLines = 1)
            MetroText(
                info.title,
                MetroType.style(m.text(if (size == TileSize.LARGE) 22f else 17f), FontWeight.SemiBold),
                color = white,
                maxLines = 2,
            )
            if (info.artist.isNotBlank()) {
                MetroText(info.artist, MetroType.style(m.text(14f)), color = white, maxLines = 1)
            }
        }
        TileLabel(app.label, m, Modifier.align(Alignment.BottomStart))
    }
}
