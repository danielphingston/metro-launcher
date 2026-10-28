package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.RecheckThrottle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecheckThrottleTest {
    private var now = 1_000_000L
    private val throttle = RecheckThrottle({ now })

    @Test
    fun firstCheckRunsThenIsThrottled() {
        assertTrue(throttle.shouldRun())
        now += 9 * 60_000L
        assertFalse(throttle.shouldRun())
    }

    @Test
    fun runsAgainAfterTheInterval() {
        assertTrue(throttle.shouldRun())
        now += RecheckThrottle.INTERVAL_MS
        assertTrue(throttle.shouldRun())
        assertFalse(throttle.shouldRun())
    }

    @Test
    fun forceBypassesTheIntervalOnce() {
        assertTrue(throttle.shouldRun())
        throttle.forceNext()
        assertTrue(throttle.shouldRun())
        assertFalse(throttle.shouldRun())
    }

    @Test
    fun checkRecordedByAConnectionCountsAsRun() {
        throttle.markRan()
        assertFalse(throttle.shouldRun())
    }

    @Test
    fun clockGoingBackwardsDoesNotBlockForever() {
        assertTrue(throttle.shouldRun())
        now -= 60_000L
        assertTrue(throttle.shouldRun())
    }
}
