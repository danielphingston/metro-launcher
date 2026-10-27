package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.applist.AlphabetScrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetScrubTest {
    @Test
    fun `a touch maps to the letter slot under it`() {
        assertEquals(0, AlphabetScrub.indexAt(0f, 280f, 28))
        assertEquals(0, AlphabetScrub.indexAt(9.9f, 280f, 28))
        assertEquals(1, AlphabetScrub.indexAt(10f, 280f, 28))
        assertEquals(27, AlphabetScrub.indexAt(279f, 280f, 28))
    }

    @Test
    fun `a touch off either end clamps to the first or last letter`() {
        assertEquals(0, AlphabetScrub.indexAt(-40f, 280f, 28))
        assertEquals(27, AlphabetScrub.indexAt(900f, 280f, 28))
    }

    @Test
    fun `an empty strip has no letter`() {
        assertEquals(-1, AlphabetScrub.indexAt(10f, 280f, 0))
        assertEquals(-1, AlphabetScrub.indexAt(10f, 0f, 5))
    }

    @Test
    fun `the finger position is in letter units`() {
        assertEquals(0f, AlphabetScrub.position(5f, 280f, 28), 1e-4f)
        assertEquals(1f, AlphabetScrub.position(15f, 280f, 28), 1e-4f)
        assertEquals(27f, AlphabetScrub.position(1000f, 280f, 28), 1e-4f)
        assertEquals(0f, AlphabetScrub.position(-5f, 280f, 28), 1e-4f)
    }

    @Test
    fun `the wave is full under the finger and gone beyond its radius`() {
        assertEquals(1f, AlphabetScrub.falloff(0f), 1e-4f)
        assertEquals(0f, AlphabetScrub.falloff(AlphabetScrub.WAVE_RADIUS), 1e-4f)
        assertEquals(0f, AlphabetScrub.falloff(-10f), 0f)
    }

    @Test
    fun `the wave falls off smoothly and the same on both sides`() {
        var last = 1f
        for (i in 1..35) {
            val d = i / 10f
            val f = AlphabetScrub.falloff(d)
            assertTrue(f <= last)
            assertEquals(f, AlphabetScrub.falloff(-d), 1e-6f)
            last = f
        }
    }

    @Test
    fun `offset and scale follow the falloff`() {
        assertEquals(40f, AlphabetScrub.offset(0f, 40f), 1e-4f)
        assertEquals(0f, AlphabetScrub.offset(5f, 40f), 1e-4f)
        assertEquals(AlphabetScrub.MAX_SCALE, AlphabetScrub.scale(0f), 1e-4f)
        assertEquals(1f, AlphabetScrub.scale(4f), 1e-4f)
    }

    @Test
    fun `the strip takes at most its share of the room and the slot cap`() {
        // 27 letters in 1000 px: the share (550) allows ~20 px a slot, the cap (17) wins only when smaller.
        assertEquals(550f / 27, AlphabetScrub.slotHeight(1000f, 27, 40f), 1e-3f)
        assertEquals(17f, AlphabetScrub.slotHeight(1000f, 27, 17f), 1e-4f)
        assertTrue(AlphabetScrub.slotHeight(1000f, 27, 100f) * 27 <= 1000f * AlphabetScrub.MAX_STRIP_FRACTION + 1e-3f)
    }

    @Test
    fun `no letters or no room gives no slot`() {
        assertEquals(0f, AlphabetScrub.slotHeight(1000f, 0, 17f), 0f)
        assertEquals(0f, AlphabetScrub.slotHeight(0f, 5, 17f), 0f)
    }

    @Test
    fun `the strip sits at the bottom with a margin`() {
        assertEquals(1000f - 16f - 459f, AlphabetScrub.stripTop(1000f, 459f, 16f), 1e-4f)
        // Always in the bottom half of the room when it is at most 55% tall.
        assertTrue(AlphabetScrub.stripTop(1000f, 550f, 16f) >= 400f)
        assertEquals(0f, AlphabetScrub.stripTop(100f, 500f, 16f), 0f)
    }

    @Test
    fun `feather layers add up to the peak alpha at the centre and none outside`() {
        val layers = 10
        var remaining = 1f
        for (i in 0 until layers) remaining *= 1f - AlphabetScrub.featherAlpha(i, layers)
        assertEquals(0f, remaining, 1e-4f)
        assertEquals(0.1f, AlphabetScrub.featherAlpha(0, layers), 1e-4f)
        var covered = 1f
        for (i in 0 until layers) covered *= 1f - AlphabetScrub.featherAlpha(i, layers, 0.6f)
        assertEquals(0.4f, covered, 1e-4f)
        assertEquals(0f, AlphabetScrub.featherAlpha(layers, layers), 0f)
    }
}
