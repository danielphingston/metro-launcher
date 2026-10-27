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
}
