package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.AppIcon
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.Metro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Pinned app shortcuts, Android's equivalent of Windows Phone secondary tiles. The system only
 * answers shortcut queries while Metro is the default home app; every call here degrades to
 * "nothing" when it isn't.
 */
object ShortcutTiles {
    private const val QUERY_FLAGS = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

    private const val MAX_ITEMS = 4

    /** Starts the shortcut; shows a toast when it was disabled or removed since it was pinned. */
    fun launch(context: Context, shortcut: TileContent.Shortcut, bounds: Rect?, options: Bundle?) {
        val user = userFor(context, shortcut.userSerial)
        val started = user != null && try {
            context.getSystemService(LauncherApps::class.java)
                .startShortcut(shortcut.packageName, shortcut.shortcutId, bounds, options, user)
            true
        } catch (_: Exception) {
            false
        }
        if (!started) Toast.makeText(context, "this shortcut isn't available", Toast.LENGTH_SHORT).show()
    }

    /**
     * Pins [shortcut] with the system so it stays valid: unpinned dynamic shortcuts disappear
     * when their app changes them. Call before adding the tile.
     */
    fun pin(context: Context, shortcut: TileContent.Shortcut) {
        val user = userFor(context, shortcut.userSerial) ?: return
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val ids = ShortcutPins.added(pinnedIds(launcherApps, shortcut.packageName, user), shortcut.shortcutId)
        guarded { launcherApps.pinShortcuts(shortcut.packageName, ids, user) }
    }

    /** Lets the system forget [shortcut] once its tile is unpinned. */
    fun release(context: Context, shortcut: TileContent.Shortcut) {
        val user = userFor(context, shortcut.userSerial) ?: return
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val ids = ShortcutPins.removed(pinnedIds(launcherApps, shortcut.packageName, user), shortcut.shortcutId)
        guarded { launcherApps.pinShortcuts(shortcut.packageName, ids, user) }
    }

    /** The first few shortcuts [app] offers, in the app's own order. Blocking: call off the main thread. */
    internal fun query(context: Context, app: AppInfo): List<ShortcutInfo> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (!hasHostPermission(launcherApps)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(QUERY_FLAGS)
            .setPackage(app.component.packageName)
            .setActivity(app.component)
        return guarded {
            launcherApps.getShortcuts(query, app.user).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(MAX_ITEMS)
        } ?: emptyList()
    }

    /** Blocking: call off the main thread. Null when the shortcut is gone or access is missing. */
    internal fun info(context: Context, shortcut: TileContent.Shortcut): ShortcutInfo? {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = userFor(context, shortcut.userSerial) ?: return null
        if (!hasHostPermission(launcherApps)) return null
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(QUERY_FLAGS)
            .setPackage(shortcut.packageName)
            .setShortcutIds(listOf(shortcut.shortcutId))
        return guarded { launcherApps.getShortcuts(query, user)?.firstOrNull() }
    }

    internal fun iconDrawable(context: Context, info: ShortcutInfo): Drawable? = guarded {
        context.getSystemService(LauncherApps::class.java)
            .getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
    }

    internal fun serialOf(context: Context, user: UserHandle): Long =
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(user)

    private fun userFor(context: Context, serial: Long): UserHandle? =
        context.getSystemService(UserManager::class.java).getUserForSerialNumber(serial)

    private fun hasHostPermission(launcherApps: LauncherApps): Boolean =
        guarded { launcherApps.hasShortcutHostPermission() } ?: false

    private fun pinnedIds(launcherApps: LauncherApps, packageName: String, user: UserHandle): List<String> {
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            .setPackage(packageName)
        return guarded { launcherApps.getShortcuts(query, user).orEmpty().map { it.id } } ?: emptyList()
    }

    /** Runs a LauncherApps call, which throws when Metro is not the default home app. */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

/** What [ShortcutTile] draws; [settled] is false while the icon is still loading. */
private class ShortcutIcon(val bitmap: ImageBitmap?, val settled: Boolean)

/** Shortcut icons by pkg/id/user/size, so scrolling and recomposition never query the system. */
private object ShortcutIconCache {
    private val cache = object : LruCache<String, ImageBitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun key(shortcut: TileContent.Shortcut, sizePx: Int, monochrome: Boolean) =
        "${shortcut.packageName}/${shortcut.shortcutId}#${shortcut.userSerial}@$sizePx${if (monochrome) "m" else "c"}"

    fun peek(key: String): ImageBitmap? = cache.get(key)

    /** Blocking: call off the main thread. */
    fun load(context: Context, shortcut: TileContent.Shortcut, sizePx: Int, monochrome: Boolean): ImageBitmap? {
        val key = key(shortcut, sizePx, monochrome)
        cache.get(key)?.let { return it }
        val info = ShortcutTiles.info(context, shortcut) ?: return null
        val drawable = ShortcutTiles.iconDrawable(context, info) ?: return null
        val bitmap = render(drawable, sizePx, monochrome)
        cache.put(key, bitmap)
        return bitmap
    }

    /** The white themed-icon rendering of [IconCache], for shortcuts that ship a monochrome layer. */
    private fun render(drawable: Drawable, sizePx: Int, monochrome: Boolean): ImageBitmap {
        if (monochrome && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val mono = (drawable as? AdaptiveIconDrawable)?.monochrome?.mutate()
            if (mono != null) {
                val bitmap = createBitmap(sizePx, sizePx)
                // Adaptive layers are 108dp with a 72dp visible area: overdraw by 1/4 each side.
                val inset = (sizePx * 0.25f).roundToInt()
                mono.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
                mono.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                mono.draw(Canvas(bitmap))
                return bitmap.asImageBitmap()
            }
        }
        return drawable.toBitmap(sizePx, sizePx).asImageBitmap()
    }
}

@Composable
fun ShortcutTile(tile: ResolvedTile, cellSize: Dp, icons: IconCache, monochrome: Boolean) {
    val shortcut = tile.spec.content as? TileContent.Shortcut ?: return PlaceholderTile(tile, cellSize)
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    val iconSize = when (size) {
        TileSize.SMALL -> m.dp(50f)
        TileSize.LARGE -> m.dp(96f)
        else -> m.dp(78f)
    }
    val context = LocalContext.current
    val px = with(LocalDensity.current) { iconSize.roundToPx() }
    val key = remember(shortcut, px, monochrome) { ShortcutIconCache.key(shortcut, px, monochrome) }
    val icon by produceState(ShortcutIcon(ShortcutIconCache.peek(key), settled = false), key) {
        // The key changed (size or monochrome), so the value may be for the old one: always reload.
        val cached = ShortcutIconCache.peek(key)
        val bitmap = cached ?: withContext(Dispatchers.Default) { ShortcutIconCache.load(context, shortcut, px, monochrome) }
        value = ShortcutIcon(bitmap, settled = true)
    }
    val bitmap = icon.bitmap
    if (bitmap == null && icon.settled) {
        PlaceholderTile(tile, cellSize)
        return
    }
    Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.align(Alignment.Center).size(iconSize))
        }
        if (size == TileSize.SMALL) return@Box
        TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
        tile.app?.let {
            AppIcon(
                it, icons, m.dp(20f), monochrome,
                Modifier.align(Alignment.BottomEnd).padding(end = m.dp(7f), bottom = m.dp(5f)),
            )
        }
    }
}

/** Extra context-menu entries for [app] in the app list, e.g. "pin “New message”". */
@Composable
fun rememberAppShortcutItems(app: AppInfo, onPin: (TileContent.Shortcut) -> Unit): List<Pair<String, () -> Unit>> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shortcuts by produceState(emptyList<ShortcutInfo>(), app.key) {
        value = withContext(Dispatchers.Default) { ShortcutTiles.query(context, app) }
    }
    return shortcuts.map { info ->
        val label = info.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: info.id
        "pin “$label”" to {
            // Binder calls stay off the main thread; the system must pin it before the tile exists.
            scope.launch {
                val content = withContext(Dispatchers.Default) {
                    val serial = ShortcutTiles.serialOf(context, info.userHandle)
                    TileContent.Shortcut(info.`package`, info.id, serial, label).also { ShortcutTiles.pin(context, it) }
                }
                onPin(content)
            }
        }
    }
}
