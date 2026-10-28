package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.AssistantModel
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.FlipCard
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/**
 * The assistant tile: the latest reply from an assistant app (read from its notifications) on the
 * front, the last question asked from search on the back. Nothing is polled; it recomposes when
 * the notification hub or the last question changes.
 */
@Composable
fun AssistantTile(
    tile: ResolvedTile,
    live: LiveContent.Assistant?,
    flipped: Boolean,
    monochrome: Boolean,
    icons: IconCache,
    cellSize: Dp,
) {
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    val model = live?.model ?: AssistantModel(tile.label, 0, null, "", null)
    FlipCard(
        showBack = flipped && model.hasBack(size),
        front = { AssistantFront(tile, size, model, monochrome, icons, m) },
        // The back stays up while the card flips back, which can outlast the live content.
        back = { live?.let { AssistantBack(tile.label, size, it.model, m) } },
    )
}

@Composable
private fun CountBadge(count: Int, m: TileMetrics, modifier: Modifier) {
    if (count <= 0) return
    Box(modifier.background(Metro.colors.onAccent).padding(horizontal = m.dp(5f), vertical = m.dp(1f))) {
        MetroText(count.coerceAtMost(99).toString(), MetroType.style(m.text(12f), FontWeight.SemiBold), color = Metro.colors.accent)
    }
}

@Composable
private fun AssistantFront(
    tile: ResolvedTile,
    size: TileSize,
    model: AssistantModel,
    monochrome: Boolean,
    icons: IconCache,
    m: TileMetrics,
) {
    val white = Metro.colors.onAccent
    val app = tile.app
    val name = tile.label
    if (size == TileSize.SMALL) {
        Box(Modifier.fillMaxSize()) {
            app?.let { AppIcon(it, icons, m.dp(40f), monochrome, Modifier.align(Alignment.Center)) }
            CountBadge(model.count, m, Modifier.align(Alignment.TopEnd).padding(top = m.dp(6f), end = m.dp(6f)))
        }
        return
    }
    val hint = if (app == null) "choose an assistant in settings" else "ask $name anything"
    if (size == TileSize.MEDIUM) {
        Box(Modifier.fillMaxSize()) {
            app?.let { AppIcon(it, icons, m.dp(36f), monochrome, Modifier.align(Alignment.TopStart).padding(m.dp(8f))) }
            if (model.count > 0) {
                MetroText(
                    model.count.coerceAtMost(99).toString(),
                    MetroType.style(m.text(34f), FontWeight.Light),
                    color = white,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = m.dp(4f), end = m.dp(10f)),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = m.dp(8f), end = m.dp(8f), bottom = m.dp(7f))) {
                if (model.hasReply) {
                    model.title?.let { MetroText(it, MetroType.style(m.text(15f), FontWeight.SemiBold), color = white, maxLines = 1) }
                    if (model.reply.isNotBlank()) {
                        MetroText(model.reply, MetroType.style(m.text(13f)), color = white.copy(alpha = 0.92f), maxLines = if (model.title == null) 4 else 3)
                    }
                } else {
                    MetroText(name, MetroType.style(m.text(15f), FontWeight.SemiBold), color = white, maxLines = 1)
                    val asked = model.lastAsked?.text
                    MetroText(
                        asked ?: hint,
                        MetroType.style(m.text(13f), if (asked != null) FontWeight.Light else FontWeight.Normal),
                        color = white.copy(alpha = 0.92f),
                        maxLines = 3,
                    )
                }
            }
        }
        return
    }
    val large = size == TileSize.LARGE
    Column(Modifier.fillMaxSize().padding(start = m.dp(10f), end = m.dp(10f), top = m.dp(9f), bottom = m.dp(7f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app?.let { AppIcon(it, icons, m.dp(if (large) 32f else 28f), monochrome) }
            Spacer(Modifier.width(m.dp(8f)))
            MetroText(name, MetroType.style(m.text(15f), FontWeight.SemiBold), color = white, maxLines = 1, modifier = Modifier.weight(1f))
            if (model.count > 0) {
                MetroText(model.count.coerceAtMost(99).toString(), MetroType.style(m.text(30f), FontWeight.Light), color = white)
            }
        }
        Spacer(Modifier.height(m.dp(6f)))
        if (model.hasReply) {
            model.title?.let {
                MetroText(it, MetroType.style(m.text(if (large) 20f else 17f), FontWeight.SemiBold), color = white, maxLines = if (large) 2 else 1)
                Spacer(Modifier.height(m.dp(2f)))
            }
            // Long replies fade out at the bottom instead of being cut off mid-line.
            Box(Modifier.weight(1f).fillMaxWidth().fadeBottom(m.dp(24f))) {
                BasicText(
                    model.reply,
                    style = MetroType.style(m.text(if (large) 16f else 14f)).copy(color = white.copy(alpha = 0.92f)),
                    overflow = TextOverflow.Clip,
                )
            }
        } else {
            val asked = model.lastAsked?.text
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                if (asked != null) {
                    MetroText("you asked", MetroType.style(m.text(13f)), color = white.copy(alpha = 0.8f))
                    MetroText(asked, MetroType.style(m.text(if (large) 28f else 20f), FontWeight.Light), color = white, maxLines = if (large) 6 else 2)
                } else {
                    MetroText(hint, MetroType.style(m.text(if (large) 28f else 22f), FontWeight.Light), color = white, maxLines = 2)
                }
            }
        }
        val asked = model.lastAsked?.text
        if (model.hasReply && asked != null) {
            Spacer(Modifier.height(m.dp(3f)))
            MetroText("you asked: $asked", MetroType.style(m.text(12f)), color = white.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

@Composable
private fun AssistantBack(name: String, size: TileSize, model: AssistantModel, m: TileMetrics) {
    val white = Metro.colors.onAccent
    val asked = model.lastAsked?.text ?: return
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = m.dp(10f), end = m.dp(10f), top = m.dp(9f))) {
            MetroText("you asked", MetroType.style(m.text(if (size == TileSize.MEDIUM) 13f else 15f)), color = white.copy(alpha = 0.8f))
            Spacer(Modifier.height(m.dp(2f)))
            MetroText(
                asked,
                MetroType.style(
                    m.text(when (size) { TileSize.MEDIUM -> 18f; TileSize.WIDE -> 26f; else -> 38f }),
                    FontWeight.Light,
                ),
                color = white,
                maxLines = when (size) { TileSize.MEDIUM -> 5; TileSize.WIDE -> 3; else -> 8 },
            )
        }
        TileLabel(name, m, Modifier.align(Alignment.BottomStart))
    }
}

/** Fades the bottom [height] of the content to transparent. */
private fun Modifier.fadeBottom(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height)
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
            blendMode = BlendMode.DstIn,
        )
    }
