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
}
