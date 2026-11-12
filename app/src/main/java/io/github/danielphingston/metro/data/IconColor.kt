package io.github.danielphingston.metro.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * The colour a tile takes from its app's icon. It shares the hue bucketing of [BackgroundAccent]
 * (which already skips transparent, near-black, near-white and grey pixels) and keeps the raw
 * tone separate from the vividness, so the setting can change without re-reading any icon.
 */
object IconColor {
    /** Icons are sampled at SAMPLE x SAMPLE pixels. */
    const val SAMPLE = BackgroundAccent.SAMPLE

    const val MIN_VIVIDNESS = 0
    const val MAX_VIVIDNESS = 100
    const val DEFAULT_VIVIDNESS = 60

    /** Below this share of opaque pixels an icon is a thin glyph and says too little about its colour. */
    private const val MIN_COVERAGE = 0.05f

    fun clampVividness(percent: Int) = percent.coerceIn(MIN_VIVIDNESS, MAX_VIVIDNESS)

    /**
     * The icon's dominant colour as drawn (opaque ARGB), or null for icons that are grey,
     * black, white, or mostly transparent: those keep the accent.
     */
    fun dominant(argb: IntArray): Int? {
        if (argb.isEmpty()) return null
        val opaque = argb.count { it ushr 24 >= 128 }
        if (opaque.toFloat() / argb.size < MIN_COVERAGE) return null
        val rgb = BackgroundAccent.dominantRgb(argb) ?: return null
        return Color(rgb[0], rgb[1], rgb[2]).toArgb()
    }

    /**
     * Moves [raw] toward the vivid, white-text-safe tone [BackgroundAccent] gives photos:
     * 0 keeps the icon's own tone, 100 is fully normalised. Saturation only ever rises with it.
     */
    fun adjust(raw: Int, vividness: Int): Int {
        val t = clampVividness(vividness) / 100f
        if (t == 0f) return raw
        val source = Color(raw)
        val target = Color(BackgroundAccent.vivid(source.red, source.green, source.blue))
        val (hue, s0, v0) = hsv(source)
        val (_, s1, v1) = hsv(target)
        return Color.hsv(hue, s0 + (s1 - s0) * t, v0 + (v1 - v0) * t).toArgb()
    }

    /** Hue (degrees), saturation and value of [color]. */
    internal fun hsv(color: Color): Triple<Float, Float, Float> {
        val top = max(color.red, max(color.green, color.blue))
        val delta = top - min(color.red, min(color.green, color.blue))
        val hue = BackgroundAccent.hue(color.red, color.green, color.blue, top, delta)
        return Triple(hue, if (top == 0f) 0f else delta / top, top)
    }
}
