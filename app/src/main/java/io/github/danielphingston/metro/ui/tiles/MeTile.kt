package io.github.danielphingston.metro.ui.tiles

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.MeFeed
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.components.LocalMotion
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
    // Each turn of the ticker moves one step: the count flips over to the newest app's notification, the
    // others slide in one by one, and after the last the tile flips back to the count.
    val faces = if (model.hasBack(size)) model.faces else emptyList()
    val cycle = remember { MeCycle() }
    val index = remember(flipped, faces) { cycle.at(flipped, faces) }
    val face = faces.getOrNull(index)
    // While flipping back to the count, the back keeps the notification it last showed.
    val held = remember { arrayOfNulls<MeFace>(1) }
    if (face != null) held[0] = face
    SideEffect { MeShown.key = face?.key }
    val apps = live?.apps.orEmpty()
    FlipCard(
        showBack = face != null,
        front = { MeFront(size, model, apps, monochrome, icons, m) },
        back = {
            (face ?: held[0])?.let { shown ->
                val motion = LocalMotion.current
                AnimatedContent(
                    targetState = shown,
                    contentKey = { it.key },
                    transitionSpec = {
                        val ms = if (motion.flipsEnabled) 450 else 0
                        (slideInVertically(tween(ms)) { it / 3 } + fadeIn(tween(ms))) togetherWith
                            (slideOutVertically(tween(ms)) { -it / 3 } + fadeOut(tween(ms)))
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    label = "me notification",
                ) { MeBack(it, size, apps, monochrome, icons, m) }
            }
        },
    )
}

/**
 * Where the me tile is: on its count (-1) or on one of [MeModel.faces]. Every change of the ticker's
 * flipped state is one step: count, 0, 1 … last, count. When the list changes (a notification comes or
 * goes) the tile stays on the notification it shows, wherever that moved; when that one is gone it
 * stays at the same place in the list (the next one), and with none left it goes back to the count.
 */
internal class MeCycle {
    private var lastFlipped: Boolean? = null
    private var index = -1
    private var key: String? = null

    fun at(flipped: Boolean, faces: List<MeFace>): Int {
        if (index >= 0) {
            val moved = faces.indexOfFirst { it.key == key }
            index = when {
                faces.isEmpty() -> -1
                moved >= 0 -> moved
                else -> index.coerceAtMost(faces.lastIndex)
            }
        }
        val turned = lastFlipped != null && lastFlipped != flipped
        lastFlipped = flipped
        if (turned) index = if (index + 1 < faces.size) index + 1 else -1
        key = faces.getOrNull(index)?.key
        return index
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
                    f.appName?.let { MetroText(it, MetroType.style(m.text(12f)), color = white.copy(alpha = 0.8f), maxLines = 1) }
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
            face.appName?.let { MetroText(it, MetroType.style(m.text(14f), FontWeight.SemiBold), color = white, maxLines = 1, modifier = Modifier.weight(1f)) }
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

    /** Sends another app's [intent]; false (never a crash) when it is cancelled or refused. */
    internal fun send(context: Context, intent: PendingIntent, options: android.os.Bundle?): Boolean {
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
        } catch (_: RuntimeException) {
            // A creator that is gone or a start the system refuses (SecurityException, IllegalStateException).
            false
        }
    }
}
