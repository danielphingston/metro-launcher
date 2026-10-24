package io.github.danielphingston.metro.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.roundToInt

/** Decodes images at the size they will be drawn, never at full resolution. */
object ImageDecode {
    /**
     * The size to decode a [srcW]x[srcH] image at so it still covers a [boxW]x[boxH] box with
     * its aspect ratio kept (the caller crops). Never larger than the source.
     */
    fun coverSize(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        val scale = maxOf(boxW.toFloat() / srcW, boxH.toFloat() / srcH)
        if (scale >= 1f) return srcW to srcH
        return maxOf(1, (srcW * scale).roundToInt()) to maxOf(1, (srcH * scale).roundToInt())
    }

    /**
     * Blocking: call off the main thread. Throws if the image can't be read, e.g.
     * [SecurityException] when access to [uri] was revoked.
     */
    fun decode(context: Context, uri: Uri, boxW: Int, boxH: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = coverSize(info.size.width, info.size.height, boxW, boxH)
            decoder.setTargetSize(w, h)
        }
    }
}
