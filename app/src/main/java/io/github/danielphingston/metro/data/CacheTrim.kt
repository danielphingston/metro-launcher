package io.github.danielphingston.metro.data

import android.content.ComponentCallbacks2
import android.util.LruCache

/** How the bitmap caches answer the system's calls to give memory back (see [keepFraction]). */
object CacheTrim {
    /**
     * The share of a cache to keep when the system reports memory [level] ([ComponentCallbacks2]):
     * everything while the launcher is on screen and memory is fine; half, then a quarter, while
     * it is on screen but the whole device runs low (RUNNING_LOW, RUNNING_CRITICAL: the visible
     * tiles' icons are the most recently used, so they stay); a third once it is hidden, which keeps the
     * icons used last (the visible tiles) so coming back home doesn't redraw them all; nothing
     * once the process is only cached in the background.
     */
    @Suppress("DEPRECATION") // RUNNING_* are deprecated but still delivered on some devices and versions.
    fun keepFraction(level: Int): Float = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0f
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 1f / 3
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 1f / 4
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 1f / 2
        else -> 1f
    }
}

/** Drops the least recently used entries until at most [keep] (0..1) of the cache's capacity is in use. */
fun <K : Any, V : Any> LruCache<K, V>.trimTo(keep: Float) {
    if (keep >= 1f) return
    if (keep <= 0f) evictAll() else trimToSize((maxSize() * keep).toInt())
}
