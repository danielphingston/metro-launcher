package io.github.danielphingston.metro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

class TileIcon(val bitmap: ImageBitmap, val isMonochrome: Boolean)

/**
 * Renders app icons once and keeps them in memory, so scrolling and recomposition never
 * touch PackageManager. Monochrome ("themed") icon layers give the flat white Metro look.
 */
class IconCache(private val context: Context) {
    private val iconPacks = IconPacks(context)

    /** Package of the active icon pack; changing it drops every cached icon. */
    @Volatile
    var iconPack: String? = null
        set(value) {
            if (field == value) return
            field = value
            cache.evictAll()
            iconColors.clear()
            generation++
        }

    /**
     * Bumped whenever cached icons become stale (icon pack switched or updated). Snapshot
     * state, so composables that read it reload their icons.
     */
    var generation by mutableIntStateOf(0)
        private set

    private val cache = object : LruCache<String, TileIcon>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: TileIcon) = value.bitmap.width * value.bitmap.height * 4
    }

    /** Raw icon colours per app (and pack), derived once; [NO_COLOUR] marks icons with no usable colour. */
    private val iconColors = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * The dominant colour of [app]'s own coloured icon (what an icon pack shows, when one is on),
     * even when tiles draw the themed monochrome one; see [IconColor.dominant]. Null when the icon
     * has none or can't be drawn. Kept in memory only, dropped with the icons. Blocking: call off
     * the main thread.
     */
    fun dominantColor(app: AppInfo): Int? {
        val key = "${app.key.serialize()}/${iconPack.orEmpty()}"
        iconColors[key]?.let { return it.takeIf { c -> c != NO_COLOUR } }
        val icon = load(app, COLOUR_SAMPLE_PX, monochrome = false) ?: return null
        val pixels = try {
            val scaled = Bitmap.createScaledBitmap(icon.bitmap.asAndroidBitmap(), IconColor.SAMPLE, IconColor.SAMPLE, true)
            IntArray(IconColor.SAMPLE * IconColor.SAMPLE).also { scaled.getPixels(it, 0, IconColor.SAMPLE, 0, 0, IconColor.SAMPLE, IconColor.SAMPLE) }
        } catch (_: RuntimeException) {
            return null
        }
        val colour = IconColor.dominant(pixels)
        iconColors[key] = colour ?: NO_COLOUR
        return colour
    }

    fun peek(app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon? = cache.get(cacheKey(app, sizePx, monochrome))

    /**
     * Blocking: call off the main thread. Null when the icon can't be drawn, for instance while
     * its app or the icon pack is being updated (the system then hands out its default icon, and
     * the pack can't be read; neither is cached, nor the app's own icon in place of the pack's);
     * the next call tries again.
     */
    fun load(app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon? {
        val key = cacheKey(app, sizePx, monochrome)
        cache.get(key)?.let { return it }
        val icon = try {
            render(app, sizePx, monochrome)
        } catch (_: RuntimeException) {
            // The drawable is the app's (or icon pack's) own code and resources: a missing resource
            // (Resources.NotFoundException), a broken vector or an unsupported size throw here.
            null
        } catch (_: OutOfMemoryError) {
            null // An enormous icon; tiles show no icon rather than the launcher dying.
        }
        icon?.let { cache.put(key, it) }
        return icon
    }

    fun installedPacks(): List<IconPackInfo> = iconPacks.installed()

    /** Drawable names of icon pack [pack]. Blocking. */
    fun packIconNames(pack: String): List<String> = iconPacks.iconNames(pack)

    /** A picked icon already rendered at [sizePx]; see [loadCustom]. */
    fun peekCustom(icon: String, sizePx: Int): ImageBitmap? = cache.get("custom/$icon@$sizePx")?.bitmap

    /**
     * A picked "pack:<package>:<drawable>" icon at [sizePx], or null (also while the pack can't be
     * read, which is not remembered: the next call tries again). Blocking: call off the main thread.
     */
    fun loadCustom(icon: String, sizePx: Int): ImageBitmap? {
        val rest = icon.removePrefix("pack:").takeIf { it != icon } ?: return null
        val split = rest.indexOf(':')
        if (split <= 0) return null
        val pack = rest.substring(0, split)
        val name = rest.substring(split + 1)
        val key = "custom/$icon@$sizePx"
        cache.get(key)?.let { return it.bitmap }
        val bitmap = try {
            val density = context.resources.displayMetrics.densityDpi
            iconPacks.drawableByName(pack, name, density)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        bitmap?.let { cache.put(key, TileIcon(it, isMonochrome = false)) }
        return bitmap
    }

    /** Gives memory back, keeping the [keep] (0..1) share of the cache used last; see [CacheTrim]. */
    fun trimMemory(keep: Float) {
        cache.trimTo(keep)
        synchronized(standInBitmaps) { standInBitmaps.clear() }
    }

    /**
     * Drops the cached icons of [packageName] and bumps [generation], so tiles showing the old
     * icon (after an app update) reload it. The bump is global: only the visible tiles redo their
     * cache lookup, and the other apps' icons are still cached.
     */
    fun evict(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach { cache.remove(it) }
        iconColors.keys.filter { it.startsWith("$packageName/") }.forEach { iconColors.remove(it) }
        generation++
    }

    /** Drops what is known about icon pack [packageName]; called when that package changed. */
    fun invalidatePack(packageName: String) {
        iconPacks.invalidate(packageName)
        cache.snapshot().keys.filter { it.startsWith("custom/pack:$packageName:") }.forEach { cache.remove(it) }
        generation++
        if (iconPack == packageName) {
            cache.evictAll()
            iconColors.clear()
            generation++
        }
    }

    private fun cacheKey(app: AppInfo, sizePx: Int, monochrome: Boolean) =
        "${app.key.serialize()}@$sizePx${if (monochrome) "m" else "c"}${iconPack.orEmpty()}"

    /**
     * The unbadged monochrome layer has lost the work-profile badge the colour path gets from
     * getBadgedIcon, so work apps would look like personal ones: ask the system to badge the
     * flat icon too. Personal apps and any failure keep the plain bitmap.
     */
    private fun badgedForWork(app: AppInfo, bitmap: Bitmap, sizePx: Int): Bitmap {
        if (app.user == Process.myUserHandle()) return bitmap
        return try {
            context.packageManager
                .getUserBadgedIcon(BitmapDrawable(context.resources, bitmap), app.user)
                .toBitmap(sizePx, sizePx)
        } catch (_: RuntimeException) {
            bitmap
        }
    }

    /**
     * True when [raw] is the system's stand-in icon although [app] declares its own: the app's
     * resources could not be read (typically while it is being updated). Caching that would keep
     * the stand-in on its tiles until the launcher restarts.
     */
    private fun isStandIn(app: AppInfo, raw: Drawable, rawBitmap: Bitmap?): Boolean {
        if (!app.declaresIcon) return false
        val fallback = context.packageManager.defaultActivityIcon
        if (raw.constantState != null && raw.constantState == fallback.constantState) return true
        return rawBitmap!!.sameAs(standInBitmap(rawBitmap.width))
    }

    /** The system default icon per size: it never changes, but icons load on several threads. */
    private val standInBitmaps = HashMap<Int, Bitmap>()

    private fun standInBitmap(sizePx: Int): Bitmap = synchronized(standInBitmaps) {
        standInBitmaps.getOrPut(sizePx) { context.packageManager.defaultActivityIcon.toBitmap(sizePx, sizePx) }
    }

    private fun render(app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon? {
        val density = context.resources.displayMetrics.densityDpi
        iconPack?.let { pack ->
            iconPacks.drawable(pack, app.component, density)?.let {
                return TileIcon(it.toBitmap(sizePx, sizePx).asImageBitmap(), isMonochrome = false)
            }
        }
        val raw = app.loadIcon(density, false)
        // Rendered once, for the stand-in check and, for personal apps, the colour icon below.
        val rawBitmap = if (app.declaresIcon) raw.toBitmap(sizePx, sizePx) else null
        if (isStandIn(app, raw, rawBitmap)) return null
        if (monochrome && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val adaptive = raw as? AdaptiveIconDrawable
            val mono = adaptive?.monochrome?.mutate()
            if (mono != null) {
                val bitmap = createBitmap(sizePx, sizePx)
                // Adaptive layers are 108dp with a 72dp visible area: overdraw by 1/4 each side.
                val inset = (sizePx * 0.25f).roundToInt()
                mono.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
                mono.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                mono.draw(Canvas(bitmap))
                return TileIcon(badgedForWork(app, bitmap, sizePx).asImageBitmap(), isMonochrome = true)
            }
        }
        // Badged and unbadged are the same for personal apps; work apps need the badged load.
        val bitmap = if (app.user == Process.myUserHandle() && rawBitmap != null) {
            rawBitmap
        } else {
            app.loadIcon(density, true).toBitmap(sizePx, sizePx)
        }
        return TileIcon(bitmap.asImageBitmap(), isMonochrome = false)
    }
}

private const val COLOUR_SAMPLE_PX = 48

/** Stored in place of a colour when an icon has none (opaque ARGB never equals 0). */
private const val NO_COLOUR = 0
