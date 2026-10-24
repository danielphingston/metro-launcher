package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.start.Turnstile
import io.github.danielphingston.metro.ui.start.TurnstilePhase
import io.github.danielphingston.metro.ui.start.TurnstileState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnstileTest {
    private val unit = 100f

    private fun delay(column: Int, row: Int, tapped: Boolean = false) =
        Turnstile.staggerDelay(column * unit, row * unit, unit, tapped)

    @Test
    fun topLeftStartsFirst() = assertEquals(0, delay(0, 0))

    @Test
    fun furtherRightAndLowerStartLater() {
        assertTrue(delay(1, 0) > delay(0, 0))
        assertTrue(delay(3, 0) > delay(1, 0))
        assertTrue(delay(0, 2) > delay(0, 0))
        assertTrue(delay(0, 4) > delay(0, 2))
        assertTrue(delay(2, 2) > delay(1, 2))
    }

    @Test
    fun stepIsAboutTwentyMillisPerColumn() = assertEquals(Turnstile.STEP_MS, delay(1, 0))

    @Test
    fun twoRowsCostAsMuchAsOneColumn() = assertEquals(delay(1, 0), delay(0, 2))

    @Test
    fun delaysAreCapped() {
        assertEquals(Turnstile.MAX_STAGGER_MS, delay(3, 20))
        assertTrue(delay(3, 20) < Turnstile.TAPPED_DELAY_MS)
    }

    @Test
    fun tappedElementGoesLast() {
        val slowest = (0..3).maxOf { column -> (0..30).maxOf { row -> delay(column, row) } }
        assertTrue(delay(0, 0, tapped = true) > slowest)
    }

    @Test
    fun theWholeRunFitsTheBudget() {
        assertTrue(Turnstile.TOTAL_MS in 250..300)
        assertEquals(Turnstile.TOTAL_MS, Turnstile.TAPPED_DELAY_MS + Turnstile.SWING_MS)
        assertTrue(Turnstile.EXIT_TIMEOUT_MS <= 350)
    }

    @Test
    fun progressIsClampedAndWaitsForItsDelay() {
        assertEquals(0f, Turnstile.progress(10f, 40), 0f)
        assertEquals(0.5f, Turnstile.progress(40f + Turnstile.SWING_MS / 2, 40), 1e-6f)
        assertEquals(1f, Turnstile.progress(1_000f, 40), 0f)
    }

    @Test
    fun leavingSwingsToEightyDegreesAndFades() {
        assertEquals(0f, Turnstile.rotation(0f, leaving = true), 0f)
        assertEquals(1f, Turnstile.alpha(0f, leaving = true), 0f)
        assertEquals(Turnstile.MAX_DEGREES, Turnstile.rotation(1f, leaving = true), 1e-4f)
        assertEquals(0f, Turnstile.alpha(1f, leaving = true), 1e-4f)
    }

    @Test
    fun returningSwingsFromMinusEightyToFlat() {
        assertEquals(-Turnstile.MAX_DEGREES, Turnstile.rotation(0f, leaving = false), 1e-4f)
        assertEquals(0f, Turnstile.alpha(0f, leaving = false), 1e-4f)
        assertEquals(0f, Turnstile.rotation(1f, leaving = false), 1e-4f)
        assertEquals(1f, Turnstile.alpha(1f, leaving = false), 1e-4f)
    }

    @Test
    fun animationsOffSkipsTheExit() = runBlocking {
        val state = TurnstileState { false }
        assertFalse(state.exit(null))
        assertFalse(state.busy)
        assertFalse(state.away)
    }

    @Test
    fun resetRestoresEverything() {
        val state = TurnstileState()
        state.seek(TurnstilePhase.OUT, 100f)
        assertTrue(state.busy)
        state.reset()
        assertFalse(state.busy)
        assertFalse(state.away)
    }
}
