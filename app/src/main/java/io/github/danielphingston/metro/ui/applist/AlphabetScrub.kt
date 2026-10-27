package io.github.danielphingston.metro.ui.applist

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** The maths of the app list's side alphabet: which letter a touch is on, and how the wave bends the strip. */
object AlphabetScrub {
    /** How many letters (each way) the wave reaches. */
    const val WAVE_RADIUS = 3.5f

    /** The touched letter is this many times its idle size. */
    const val MAX_SCALE = 2.1f

    /** The index of the letter under [y] in a strip [height] tall split into [count] equal slots; -1 if empty. */
    fun indexAt(y: Float, height: Float, count: Int): Int {
        if (count <= 0 || height <= 0f) return -1
        return (y / height * count).toInt().coerceIn(0, count - 1)
    }

    /** The finger's position in letter units: 0.0 is the middle of the first letter, 1.0 the second's, and so on. */
    fun position(y: Float, height: Float, count: Int): Float {
        if (count <= 0 || height <= 0f) return 0f
        return (y / height * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    }

    /** 1 under the finger, easing to 0 at [WAVE_RADIUS] letters away (a raised cosine). */
    fun falloff(distance: Float): Float {
        val d = abs(distance)
        return if (d >= WAVE_RADIUS) 0f else 0.5f * (1f + cos(PI.toFloat() * d / WAVE_RADIUS))
    }

    /** How far a letter [distance] letters from the finger slides toward the screen centre, at most [max]. */
    fun offset(distance: Float, max: Float): Float = max * falloff(distance)

    /** The size of a letter [distance] letters from the finger, relative to its idle size. */
    fun scale(distance: Float): Float = 1f + (MAX_SCALE - 1f) * falloff(distance)

    /** The strip may take at most this share of the room it has, so it stays in reach of a thumb. */
    const val MAX_STRIP_FRACTION = 0.55f

    /** The height of one letter slot: the room allows [MAX_STRIP_FRACTION] of [available] split over [count], capped at [maxSlot]. */
    fun slotHeight(available: Float, count: Int, maxSlot: Float): Float {
        if (count <= 0 || available <= 0f) return 0f
        return minOf(available * MAX_STRIP_FRACTION / count, maxSlot)
    }

    /** The top of a strip [strip] tall [margin] above the bottom of [available], never above its top (the search box). */
    fun stripTop(available: Float, strip: Float, margin: Float): Float = (available - margin - strip).coerceAtLeast(0f)

    /** The fixed highlight's vertical centre sits this far down the list, about eye level. */
    const val FIXED_CENTRE_FRACTION = 0.33f

    /** The vertical centre of the fixed highlight in a list [available] tall. */
    fun fixedCentre(available: Float): Float = available * FIXED_CENTRE_FRACTION

    /** The gap between the strip and the bottom of [available] for a setting of [percent] of its height. */
    fun bottomGap(available: Float, percent: Int): Float = available * percent.coerceIn(0, 100) / 100f

    /**
     * The alpha to paint layer [index] of [layers] stacked, shrinking shapes (outermost first) so the alpha
     * accumulated over them reaches [peak] at the centre and rises evenly from the edge: a feathered edge.
     */
    fun featherAlpha(index: Int, layers: Int, peak: Float = 1f): Float {
        if (layers <= 0 || index !in 0 until layers) return 0f
        val before = peak * index / layers
        val after = peak * (index + 1) / layers
        return (after - before) / (1f - before)
    }
}
