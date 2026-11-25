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
        update(0, shown(1, 2), frame, gridChanges = false, scrolledPx = { 0 }) { true }
        assertTrue(update(1, shown(2, 3), frame, gridChanges = true, scrolledPx = { 0 }) { animate })
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
        motion.update(2, shown(3, 4), frame, gridChanges = false, scrolledPx = { 0 }) { true }
        first.cancel()
        first.join()
        assertTrue("the newer switch is still to be played", motion.active)
        withTimeout(5_000) { motion.play(260) }
        assertFalse(motion.active)
        assertTrue(motion.leavingKeys().isEmpty())
    }

    @Test
    fun aScrolledStartKeepsItsTilesStillBeforeAndAfterTheJumpToTheTop() {
        var scroll = 500
        val was = Placement(0, 10, 2, 2)
        val gone = Placement(2, 10, 2, 2)
        val now = Placement(0, 0, 2, 2)
        val motion = SwitchMotion()
        motion.update(0, mapOf(1L to (tile(1) to was), 2L to (tile(2) to gone)), frame, gridChanges = false, scrolledPx = { scroll }) { true }
        assertTrue(motion.update(1, mapOf(1L to (tile(1) to now)), frame, gridChanges = false, scrolledPx = { scroll }) { true })
        // The frames before the switch jumps start to the top: drawn exactly where they were (these flickered).
        assertEquals(frame.rectOf(was), motion.drawnRect(1L, now, frame).first)
        assertEquals(frame.rectOf(gone), motion.drawnRect(2L, gone, frame, isLeaving = true).first)
        // After the jump start is at its top: 500 px higher in the content, which is the same place on screen.
        scroll = 0
        assertEquals(frame.rectOf(was).let { it.copy(top = it.top - 500f) }, motion.drawnRect(1L, now, frame).first)
        assertEquals(frame.rectOf(gone).let { it.copy(top = it.top - 500f) }, motion.drawnRect(2L, gone, frame, isLeaving = true).first)
    }

    @Test
    fun aSwitchDuringASwitchStartsFromWhereTheTileIsDrawn() = runBlocking(Frames) {
        val a = Placement(0, 0, 2, 2)
        val b = Placement(0, 8, 2, 2)
        val c = Placement(2, 4, 2, 2)
        val motion = SwitchMotion()
        motion.update(0, mapOf(1L to (tile(1) to a)), frame, gridChanges = false, scrolledPx = { 0 }) { true }
        motion.update(1, mapOf(1L to (tile(1) to b)), frame, gridChanges = false, scrolledPx = { 0 }) { true }
        assertTrue(motion.takeSwitch())
        val first = async { motion.play(1_000) }
        repeat(20) { yield() }
        val midway = motion.drawnRect(1L, b, frame).first
        assertTrue("the first switch is under way", midway != frame.rectOf(a) && midway != frame.rectOf(b))
        // A second switch arrives; its effect cancels the first one's play.
        motion.update(2, mapOf(1L to (tile(1) to c)), frame, gridChanges = false, scrolledPx = { 0 }) { true }
        first.cancel()
        first.join()
        // It starts from where the tile was drawn, not from the first switch's start (a) or end (b).
        assertEquals(midway, motion.drawnRect(1L, c, frame).first)
    }

    @Test
    fun withoutASwitchNothingChanges() {
        val motion = SwitchMotion()
        assertFalse(motion.update(0, shown(1), frame, gridChanges = false, scrolledPx = { 0 }) { true })
        assertFalse(motion.update(0, shown(1, 2), frame, gridChanges = false, scrolledPx = { 0 }) { error("only asked on a switch") })
        assertFalse(motion.takeSwitch())
        assertFalse(motion.active)
    }
}
