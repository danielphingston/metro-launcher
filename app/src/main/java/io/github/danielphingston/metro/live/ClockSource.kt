package io.github.danielphingston.metro.live

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
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
        val clockApps = clockPackages(context)
        while (true) {
            val now = System.currentTimeMillis()
            val next = alarmManager.nextAlarmClock
            val fromClock = isFromClockApp(next?.showIntent?.creatorPackage, clockApps)
            emit(ClockState(now, next?.triggerTime?.takeIf { fromClock }))
            delay(60_000 - now % 60_000 + 50)
        }
    }

    /**
     * Whether the next "alarm clock" came from a clock app. Other apps (reminders, sleep trackers,
     * some system services) also schedule alarm clocks, and those aren't the user's alarm.
     */
    internal fun isFromClockApp(creator: String?, clockApps: Set<String>): Boolean =
        creator != null && (clockApps.isEmpty() || creator in clockApps)

    /** Apps that can show or set alarms; empty if none can be seen, which keeps every alarm. */
    private fun clockPackages(context: Context): Set<String> {
        val pm = context.packageManager
        return listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM).flatMapTo(HashSet()) { action ->
            pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        }
    }
}
