package io.github.danielphingston.metro.live

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ClockState(val now: Long, val nextAlarm: Long?)

object ClockSource {
    /**
     * Emits at each minute boundary. A plain coroutine delay (no broadcast receiver or alarm):
     * it only runs while collected, i.e. while the start screen is on screen.
     */
    fun minutes(context: Context): Flow<ClockState> = flow {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        while (true) {
            val now = System.currentTimeMillis()
            emit(ClockState(now, alarmManager.nextAlarmClock?.triggerTime))
            delay(60_000 - now % 60_000 + 50)
        }
    }
}
