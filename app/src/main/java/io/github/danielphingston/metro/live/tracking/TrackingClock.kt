package io.github.danielphingston.metro.live.tracking

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The tracking tile's minute ticks. */
object TrackingClock {
    /**
     * Emits now and then at each minute boundary. A plain coroutine delay, no alarm or receiver: it
     * only runs while collected, which the start screen does only while it is visible and an ETA is shown.
     */
    fun minutes(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(60_000 - now % 60_000 + 50)
        }
    }
}
