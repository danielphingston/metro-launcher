package io.github.danielphingston.metro.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class CalendarEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
)

object CalendarSource {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Upcoming events, re-queried only when the calendar provider reports a change or every
     * few minutes (so past events drop off). Collect it only while the start screen is visible.
     */
    fun upcoming(context: Context, limit: Int = 3): Flow<List<CalendarEvent>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        val ticker = launch {
            while (true) {
                send(Unit)
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
        awaitClose {
            ticker.cancel()
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.conflate().map { query(context, limit) }.flowOn(Dispatchers.IO)

    private fun query(context: Context, limit: Int): List<CalendarEvent> {
        if (!hasPermission(context)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - TimeUnit.DAYS.toMillis(1))
            android.content.ContentUris.appendId(it, now + TimeUnit.DAYS.toMillis(7))
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = ArrayList<CalendarEvent>()
        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(3) == 1
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    // All-day instances are stored in UTC; shift them to local midnight.
                    val offset = if (allDay) -java.util.TimeZone.getDefault().getOffset(begin).toLong() else 0L
                    val event = CalendarEvent(
                        title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)",
                        begin = begin + offset,
                        end = end + offset,
                        allDay = allDay,
                        location = c.getString(4)?.takeIf { it.isNotBlank() },
                    )
                    if (event.end > now) events.add(event)
                }
            }
        }
        // Timed events first (what's next), all-day events after.
        return events.sortedWith(compareBy<CalendarEvent> { it.allDay }.thenBy { it.begin }).take(limit)
    }
}
