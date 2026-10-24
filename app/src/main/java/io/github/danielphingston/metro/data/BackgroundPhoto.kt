package io.github.danielphingston.metro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.danielphingston.metro.live.ImageDecode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photo behind the start screen. Decoded once, downsampled to the screen plus room for
 * parallax, and held as a single bitmap.
 */
object BackgroundPhoto {
    /**
     * How much taller than the screen the bitmap is: the extra height is what the parallax
     * scroll reveals.
     */
    const val HEADROOM = 0.2f

    /** The image moves at this fraction of the start screen's scroll speed. */
    const val PARALLAX = 0.2f

    /**
     * The largest centred crop of a [srcW]x[srcH] image with the aspect ratio of [boxW]x[boxH].
     */
    fun centerCrop(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Rect {
        val aspect = boxW.toFloat() / boxH
        val w = min(srcW, (srcH * aspect).roundToInt())
        val h = min(srcH, (srcW / aspect).roundToInt())
        val left = (srcW - w) / 2
        val top = (srcH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Blocking: call off the main thread. Decodes [uri] to cover the screen
     * ([screenW]x[screenH]) plus the parallax headroom, cropped around the centre. Throws if the
     * image can't be read, e.g. [SecurityException] when access to [uri] was revoked.
     */
    fun decode(context: Context, uri: Uri, screenW: Int, screenH: Int): Bitmap {
        val boxH = (screenH * (1f + HEADROOM)).roundToInt()
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = ImageDecode.coverSize(info.size.width, info.size.height, screenW, boxH)
            decoder.setTargetSize(w, h)
            decoder.setCrop(centerCrop(w, h, screenW, boxH))
        }
    }

    /** Blocking: a vivid accent for [uri], or null if it has no usable colour or can't be read. */
    fun deriveAccent(context: Context, uri: Uri): Int? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setTargetSize(BackgroundAccent.SAMPLE, BackgroundAccent.SAMPLE)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        BackgroundAccent.fromPixels(pixels)
    }.getOrNull()
}

/** Picks an accent colour from a photo without any palette library. */
object BackgroundAccent {
    /** Photos are sampled at SAMPLE x SAMPLE pixels. */
    const val SAMPLE = 24

    private const val BUCKETS = 12
    private const val MIN_SATURATION = 0.35f
    private const val MIN_VALUE = 0.35f

    /**
     * The average colour of the hue bucket with the most saturated, bright pixels, adjusted to
     * be vivid but dark enough for white tile text. Null when the photo is grey or very dark.
     */
    fun fromPixels(argb: IntArray): Int? {
        val weight = FloatArray(BUCKETS)
        val red = FloatArray(BUCKETS)
        val green = FloatArray(BUCKETS)
        val blue = FloatArray(BUCKETS)
        for (pixel in argb) {
            if (pixel ushr 24 < 128) continue
            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8 and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val top = max(r, max(g, b))
            val delta = top - min(r, min(g, b))
            if (top < MIN_VALUE || delta / top < MIN_SATURATION) continue
            val bucket = (hue(r, g, b, top, delta) / (360f / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
            val w = delta // saturation * brightness
            weight[bucket] += w
            red[bucket] += r * w
            green[bucket] += g * w
            blue[bucket] += b * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] <= 0f) return null
        return vivid(red[best] / weight[best], green[best] / weight[best], blue[best] / weight[best])
    }

    private fun hue(r: Float, g: Float, b: Float, top: Float, delta: Float): Float = when {
        delta == 0f -> 0f
        top == r -> ((g - b) / delta + 6f) % 6f * 60f
        top == g -> ((b - r) / delta + 2f) * 60f
        else -> ((r - g) / delta + 4f) * 60f
    }

    /** Raises saturation and caps brightness, so the accent carries white text. */
    private fun vivid(r: Float, g: Float, b: Float): Int {
        val top = max(r, max(g, b))
        val delta = top - min(r, min(g, b))
        val hue = hue(r, g, b, top, delta)
        val saturation = max(delta / top, 0.7f)
        var value = top.coerceIn(0.6f, 0.9f)
        var color = Color.hsv(hue, saturation, value)
        // Yellows and greens are light at full value: darken until white text reads.
        while (color.luminance() > 0.35f && value > 0.4f) {
            value -= 0.05f
            color = Color.hsv(hue, saturation, value)
        }
        return color.toArgb()
    }
}
