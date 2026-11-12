package io.github.danielphingston.metro.ui.tiles

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.MeFeed
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.FlipCard
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/**
 * The me tile: how many notifications are waiting across all apps and whose they are on the front,
 * the latest few one by one on the back. Nothing is polled; it recomposes when the listener
 * publishes a change, and the shared flip ticker turns it only while the launcher is visible.
 */
@Composable
fun MeTile(
    tile: io.github.danielphingston.metro.ui.ResolvedTile,
    live: LiveContent.Me?,
    flipped: Boolean,
    monochrome: Boolean,
    icons: IconCache,
    cellSize: Dp,
) {
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    val model = live?.model ?: MeModel(MeModel.State.EMPTY, 0, emptyList(), emptyList())
    // Each flip to the back moves on to the next notification; the back keeps its face while it turns back.
    val cursor = remember { FaceCursor() }
    val index = remember(flipped) { cursor.advance(flipped) }
    val showBack = flipped && model.hasBack(size)
    val face = model.faces.takeIf { it.isNotEmpty() }?.get(index % model.faces.size)
    SideEffect { MeShown.key = if (showBack) face?.key else null }
    FlipCard(
        showBack = showBack,
        front = { MeFront(size, model, live?.apps.orEmpty(), monochrome, icons, m) },
        back = { face?.let { MeBack(it, size, live?.apps.orEmpty(), monochrome, icons, m) } },
    )
}

private class FaceCursor {
    private var next = 0
    private var shown = 0
    fun advance(flipped: Boolean): Int {
        if (flipped) shown = next++
        return shown
    }
}

@Composable
private fun MeFront(size: TileSize, model: MeModel, apps: Map<String, AppInfo>, monochrome: Boolean, icons: IconCache, m: TileMetrics) {
    val white = Metro.colors.onAccent
    when (model.state) {
        MeModel.State.NO_ACCESS -> Box(Modifier.fillMaxSize()) {
            MetroText(
                "turn on notification access",
                MetroType.style(m.text(if (size == TileSize.SMALL) 12f else 16f), FontWeight.SemiBold),
                color = white,
                maxLines = if (size == TileSize.SMALL) 4 else 3,
                modifier = Modifier.align(Alignment.TopStart).padding(m.dp(8f)),
            )
            TileLabel("me", m, Modifier.align(Alignment.BottomStart))
        }
        MeModel.State.EMPTY -> Box(Modifier.fillMaxSize()) {
            MetroText(
                "nothing new",
                MetroType.style(m.text(if (size == TileSize.SMALL) 13f else 20f), FontWeight.Light),
                color = white.copy(alpha = 0.9f),
                maxLines = 2,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = m.dp(8f)),
            )
            TileLabel("me", m, Modifier.align(Alignment.BottomStart))
        }
        MeModel.State.ACTIVE -> {
            val count = model.count.coerceAtMost(99).toString()
            if (size == TileSize.SMALL) {
                Box(Modifier.fillMaxSize()) {
                    MetroText(count, MetroType.style(m.text(44f), FontWeight.Light), color = white, modifier = Modifier.align(Alignment.Center))
                    TileLabel("me", m, Modifier.align(Alignment.BottomStart))
                }
                return
            }
            val large = size == TileSize.LARGE
            Column(Modifier.fillMaxSize().padding(start = m.dp(8f), end = m.dp(8f), top = m.dp(7f), bottom = m.dp(6f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(m.dp(4f))) {
                        model.icons.forEach { key ->
                            apps[key]?.let { AppIcon(it, icons, m.dp(if (size == TileSize.MEDIUM) 24f else 28f), monochrome) }
                        }
                    }
                    MetroText(count, MetroType.style(m.text(if (size == TileSize.MEDIUM) 34f else 40f), FontWeight.Light), color = white)
                }
                Spacer(Modifier.weight(1f))
                model.latest?.let { f ->
                    MetroText(f.appName, MetroType.style(m.text(12f)), color = white.copy(alpha = 0.8f), maxLines = 1)
                    f.title?.let { MetroText(it, MetroType.style(m.text(if (large) 20f else 15f), FontWeight.SemiBold), color = white, maxLines = if (large) 2 else 1) }
                    f.text?.let {
                        MetroText(it, MetroType.style(m.text(if (large) 16f else 13f)), color = white.copy(alpha = 0.92f), maxLines = if (large) 4 else if (size == TileSize.MEDIUM) 1 else 2)
                    }
                }
                Spacer(Modifier.height(m.dp(2f)))
                MetroText("me", MetroType.style(m.text(12f)), color = white.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MeBack(face: MeFace, size: TileSize, apps: Map<String, AppInfo>, monochrome: Boolean, icons: IconCache, m: TileMetrics) {
    val white = Metro.colors.onAccent
    val large = size == TileSize.LARGE
    Column(Modifier.fillMaxSize().padding(start = m.dp(10f), end = m.dp(10f), top = m.dp(9f), bottom = m.dp(7f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            apps[face.appKey]?.let {
                AppIcon(it, icons, m.dp(if (size == TileSize.MEDIUM) 24f else 28f), monochrome)
                Spacer(Modifier.width(m.dp(8f)))
            }
            MetroText(face.appName, MetroType.style(m.text(14f), FontWeight.SemiBold), color = white, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(m.dp(6f)))
        face.title?.let {
            MetroText(it, MetroType.style(m.text(if (large) 22f else 17f), FontWeight.SemiBold), color = white, maxLines = if (large) 2 else 1, modifier = Modifier.fillMaxWidth())
        }
        face.text?.let {
            MetroText(
                it, MetroType.style(m.text(if (large) 18f else 14f)), color = white.copy(alpha = 0.92f),
                maxLines = when (size) { TileSize.MEDIUM -> 2; TileSize.WIDE -> 3; else -> 8 },
            )
        }
    }
}

/** The notification the me tile shows right now (null on the front), read at tap time. */
internal object MeShown {
    @Volatile var key: String? = null
}

internal object MeTiles {
    /** What a tap does: open the shown notification, else its app; the tiles settings page without access. */
    fun activate(context: Context, bounds: androidx.compose.ui.geometry.Rect?, launcher: AppLauncher) {
        if (!NotificationAccess.isGranted(context)) {
            launcher.start(SettingsActivity.intent(context, SettingsPage.TILES), bounds)
            return
        }
        val target = MeTap.resolve(MeFeed.snapshot.value, MeShown.key) ?: return
        val appIntent = { context.packageManager.getLaunchIntentForPackage(target.packageName) }
        if (target.intent == null) {
            appIntent()?.let { launcher.start(it, bounds) }
            return
        }
        launcher.open(bounds) { _, options ->
            send(context, target.intent, options) || appIntent()?.let { i ->
                runCatching { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), options) }.isSuccess
            } == true
        }
    }

    private fun send(context: Context, intent: PendingIntent, options: android.os.Bundle?): Boolean {
        val bundle = android.os.Bundle()
        options?.let(bundle::putAll)
        // The launcher is visible when the tile is tapped; Android 14+ wants that said explicitly.
        if (Build.VERSION.SDK_INT >= 34) {
            bundle.putAll(
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle(),
            )
        }
        return try {
            intent.send(context, 0, null, null, null, null, bundle)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}
