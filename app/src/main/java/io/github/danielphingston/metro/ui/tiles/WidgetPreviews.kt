package io.github.danielphingston.metro.ui.tiles

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Widget preview thumbnails for the picker. Decoding a provider's preview can load a large
 * drawable from another app, so results are kept in a size-bounded cache (scrolling back doesn't
 * decode again) and at most [MAX_DECODES] run at once, so a fast scroll doesn't flood the disk.
 */
internal object WidgetPreviews {
    private const val MAX_DECODES = 2
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PX = 384

    private val permits = Semaphore(MAX_DECODES)
    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    /** The cache key for a provider in a profile. */
    fun key(provider: String, userSerial: Long): String = "$provider#$userSerial"

    fun peek(key: String): ImageBitmap? = cache.get(key)

    /** The preview for [key], from the cache or decoded from [load]'s drawable (null when there is none). */
    suspend fun get(key: String, load: () -> Drawable?): ImageBitmap? {
        cache.get(key)?.let { return it }
        return permits.withPermit {
            cache.get(key) ?: withContext(Dispatchers.IO) {
                runCatching { load()?.toPreviewBitmap()?.asImageBitmap() }.getOrNull()
            }?.also { cache.put(key, it) }
        }
    }

    /** Renders a preview no larger than a list thumbnail, so big previews don't waste memory. */
    private fun Drawable.toPreviewBitmap(): Bitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: MAX_PX
        val h = intrinsicHeight.takeIf { it > 0 } ?: MAX_PX
        val scale = min(1f, MAX_PX.toFloat() / max(w, h))
        return toBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
    }
}
