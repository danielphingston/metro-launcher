package io.github.danielphingston.metro

import androidx.compose.runtime.MonotonicFrameClock
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.GridFrame
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.SwitchMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A switch always ends and lets go of what it drew, however its coroutine ends. */
class SwitchMotionTest {
    /** Frames 16 ms apart, as fast as the test can take them. */
    private object Frames : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            nanos += 16_000_000
            return onFrame(nanos)
        }
    }

    private val frame = GridFrame(0f, 0f, 100f, 8f)
    private fun tile(id: Long) = ResolvedTile(TileSpec(id, TileSize.MEDIUM, TileContent.App(AppKey("p$id", "p$id.Main", 0))), null, "t$id")
    private fun shown(vararg ids: Long) = ids.associate { it to (tile(it) to Placement(0, it.toInt() * 2, 2, 2)) }

    /** A motion that has shown tiles 1 and 2 and then switched to tiles 2 and 3. */
    private fun switched(animate: Boolean): SwitchMotion = SwitchMotion().apply {
        update(0, shown(1, 2), frame, gridChanges = false) { true }
        assertTrue(update(1, shown(2, 3), frame, gridChanges = true) { animate })
        assertTrue(takeSwitch())
    }

    private fun SwitchMotion.leavingKeys() = onScreen(emptyList(), emptyList()) { it }.map { it.key }

    @Test
    fun aPlayedSwitchEndsAndClears() = runBlocking(Frames) {
        val motion = switched(animate = true)
        assertTrue(motion.active)
        assertEquals(listOf(1L), motion.leavingKeys())
        withTimeout(5_000) { motion.play(260) }
        assertFalse(motion.active)
        assertFalse(motion.waitsForGrid)
        assertTrue(motion.leavingKeys().isEmpty())
        assertEquals(1, motion.ended)
    }

    @Test
    fun aSwitchCancelledWhileWaitingForItsSettingsStillClears() = runBlocking(Frames) {
        val motion = switched(animate = true)
        val waiting = CompletableDeferred<Unit>()
        val play = async { motion.play(260) { waiting.complete(Unit); awaitCancellation() } }
        waiting.await()
        play.cancel()
        play.join()
        assertFalse(motion.active)
        assertTrue(motion.leavingKeys().isEmpty())
        assertEquals(1, motion.ended)
    }

    @Test
    fun withAnimationsOffNothingIsPlannedOrLeftBehind() = runBlocking(Frames) {
        val motion = switched(animate = false)
        assertFalse(motion.active)
        assertTrue(motion.leavingKeys().isEmpty())
        // The screen still calls play; it returns at once without waiting or animating.
        var waited = false
        withTimeout(1_000) { motion.play(260) { waited = true } }
        assertFalse(waited)
        assertEquals(1, motion.ended)
    }

    @Test
    fun anOlderPlayCancelledByANewerSwitchLeavesTheNewOneAlone() = runBlocking(Frames) {
        val motion = switched(animate = true)
        val first = async { motion.play(10_000) }
        repeat(3) { yield() }
        // A second switch arrives mid-way; the first play is cancelled as its effect restarts.
        motion.update(2, shown(3, 4), frame, gridChanges = false) { true }
        first.cancel()
        first.join()
        assertTrue("the newer switch is still to be played", motion.active)
        withTimeout(5_000) { motion.play(260) }
        assertFalse(motion.active)
        assertTrue(motion.leavingKeys().isEmpty())
    }

    @Test
    fun withoutASwitchNothingChanges() {
        val motion = SwitchMotion()
        assertFalse(motion.update(0, shown(1), frame, gridChanges = false) { true })
        assertFalse(motion.update(0, shown(1, 2), frame, gridChanges = false) { error("only asked on a switch") })
        assertFalse(motion.takeSwitch())
        assertFalse(motion.active)
    }
}
