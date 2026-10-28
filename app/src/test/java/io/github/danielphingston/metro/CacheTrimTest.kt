package io.github.danielphingston.metro

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.LruCache
import io.github.danielphingston.metro.data.CacheTrim
import io.github.danielphingston.metro.data.trimTo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class CacheTrimTest {
    @Test
    @Suppress("DEPRECATION")
    fun keepsEverythingWhileVisibleAThirdWhenHiddenNothingInTheBackground() {
        val low = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        val critical = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        assertEquals(1f, CacheTrim.keepFraction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE), 0f)
        assertEquals(1f, CacheTrim.keepFraction(low - 1), 0f)
        assertEquals(1f / 2, CacheTrim.keepFraction(low), 0f)
        assertEquals(1f / 4, CacheTrim.keepFraction(critical), 0f)
        val hidden = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        assertEquals(1f / 4, CacheTrim.keepFraction(hidden - 1), 0f)
        assertEquals(1f / 3, CacheTrim.keepFraction(hidden), 0f)
        assertEquals(1f / 3, CacheTrim.keepFraction(hidden + 1), 0f)
        val background = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        assertEquals(1f / 3, CacheTrim.keepFraction(background - 1), 0f)
        assertEquals(0f, CacheTrim.keepFraction(background), 0f)
        assertEquals(0f, CacheTrim.keepFraction(background + 40), 0f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LruCacheTrimTest {
    private fun filled() = object : LruCache<Int, ByteArray>(90) {
        override fun sizeOf(key: Int, value: ByteArray) = value.size
    }.also { cache -> for (i in 0 until 9) cache.put(i, ByteArray(10)) }

    @Test
    fun trimsLeastRecentlyUsedFirst() {
        val cache = filled()
        cache.get(0) // 0 is now the most recently used
        cache.trimTo(0.3f) // Room for 27 of 90 bytes: two entries.
        assertEquals(setOf(0, 8), cache.snapshot().keys)
    }

    @Test
    fun zeroEmptiesAndOneChangesNothing() {
        val cache = filled()
        cache.trimTo(1f)
        assertEquals(90, cache.size())
        cache.trimTo(0f)
        assertEquals(0, cache.size())
    }
}
