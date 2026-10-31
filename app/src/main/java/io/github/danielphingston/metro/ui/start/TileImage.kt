package io.github.danielphingston.metro.ui.start

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.danielphingston.metro.live.ImageDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photos picked for tile styles, decoded at the size they are shown and kept so scrolling doesn't reload them. */
internal object TileImages {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(key: String): ImageBitmap? = cache.get(key)

    /** Blocking. Null when the photo can't be read, e.g. it was deleted or access was revoked. */
    fun load(context: Context, uri: String, width: Int, height: Int): ImageBitmap? = try {
        ImageDecode.decode(context, uri.toUri(), width, height).asImageBitmap()
            .also { cache.put("$uri@${width}x$height", it) }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** The photo at [uri], cropped to fill [modifier]'s bounds; nothing while it loads or if it can't be read. */
@Composable
internal fun TileImage(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.coerceIn(1, 2048)
        val h = constraints.maxHeight.coerceIn(1, 2048)
        val key = "$uri@${w}x$h"
        val bitmap by produceState(TileImages.peek(key), key) {
            value = TileImages.peek(key) ?: withContext(Dispatchers.IO) { TileImages.load(context, uri, w, h) } ?: value
        }
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
