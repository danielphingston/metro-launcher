package io.github.danielphingston.metro.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
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

    fun peek(app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon? = cache.get(cacheKey(app, sizePx, monochrome))

    /** Blocking: call off the main thread. */
    fun load(app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon {
        val key = cacheKey(app, sizePx, monochrome)
        cache.get(key)?.let { return it }
        val icon = render(app, sizePx, monochrome)
        cache.put(key, icon)
        return icon
    }

    fun evict(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach { cache.remove(it) }
    }

    /** Drops what is known about icon pack [packageName]; called when that package changed. */
    fun invalidatePack(packageName: String) {
        iconPacks.invalidate(packageName)
        if (iconPack == packageName) {
            cache.evictAll()
            generation++
        }
    }

    private fun cacheKey(app: AppInfo, sizePx: Int, monochrome: Boolean) =
        "${app.key.serialize()}@$sizePx${if (monochrome) "m" else "c"}${iconPack.orEmpty()}"

    private fun render(app: AppInfo, sizePx: Int, monochrome: Boolean): TileIcon {
        val density = context.resources.displayMetrics.densityDpi
        iconPack?.let { pack ->
            iconPacks.drawable(pack, app.component, density)?.let {
                return TileIcon(it.toBitmap(sizePx, sizePx).asImageBitmap(), isMonochrome = false)
            }
        }
        if (monochrome && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val adaptive = app.loadIcon(density, false) as? AdaptiveIconDrawable
            val mono = adaptive?.monochrome?.mutate()
            if (mono != null) {
                val bitmap = createBitmap(sizePx, sizePx)
                // Adaptive layers are 108dp with a 72dp visible area: overdraw by 1/4 each side.
                val inset = (sizePx * 0.25f).roundToInt()
                mono.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
                mono.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                mono.draw(Canvas(bitmap))
                return TileIcon(bitmap.asImageBitmap(), isMonochrome = true)
            }
        }
        val drawable = app.loadIcon(density, true)
        return TileIcon(drawable.toBitmap(sizePx, sizePx).asImageBitmap(), isMonochrome = false)
    }
}
