package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TapCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapCounterTest {
    @Test
    fun triggersOnTheSeventhQuickTapThenStartsOver() {
        val taps = TapCounter()
        repeat(6) { assertFalse(taps.tap(it * 500L)) }
        assertTrue(taps.tap(3_000))
        repeat(6) { assertFalse(taps.tap(10_000 + it * 500L)) }
        assertTrue(taps.tap(13_000))
    }

    @Test
    fun aSlowTapStartsTheCountAgain() {
        val taps = TapCounter()
        repeat(5) { taps.tap(it * 500L) }
        assertFalse(taps.tap(10_000)) // too late: this is tap 1 of a new run
        var fired = 0
        for (i in 1..6) if (taps.tap(10_000 + i * 500L)) fired++
        assertEquals(1, fired)
    }
}
