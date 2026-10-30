package io.github.danielphingston.metro.ui.search

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.github.danielphingston.metro.data.SmartFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * An upcoming event. [begin] and [end] are the shown times (all-day events moved from the UTC
 * midnight the calendar stores to local midnight); [rawBegin] and [rawEnd] are what the calendar app
 * expects back when opening the event.
 */
internal data class CalendarHit(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val rawBegin: Long,
    val rawEnd: Long,
    val allDay: Boolean,
    val location: String?,
)

/** Pure matching and wording for the calendar feature. */
internal object CalendarMatcher {
    /** How far ahead events are searched. */
    val HORIZON_MILLIS: Long = TimeUnit.DAYS.toMillis(30)

    /** The best [limit] of [events] whose title or place matches [query]; better matches first, then the soonest. */
    fun top(events: List<CalendarHit>, query: String, limit: Int): List<CalendarHit> {
        if (query.isBlank()) return emptyList()
        return events
            .mapNotNull { event ->
                val rank = listOfNotNull(event.title, event.location).mapNotNull { SearchRanking.rank(it, query) }.minOrNull()
                    ?: return@mapNotNull null
                event to rank
            }
            .sortedWith(compareBy({ it.second }, { it.first.begin }))
            .take(limit)
            .map { it.first }
    }

    /**
     * When an event happens, in words: "today 14:00", "tomorrow, all day", "fri 14 nov 09:30". The
     * time follows the phone's 12 or 24 hour clock; [now] and [zone] decide what today is.
     */
    fun whenText(hit: CalendarHit, now: Long, zone: TimeZone, locale: Locale, is24: Boolean): String {
        val dayDiff = daysBetween(now, hit.begin, zone)
        val day = when {
            dayDiff <= 0 && hit.begin <= now -> "now"
            dayDiff == 0 -> "today"
            dayDiff == 1 -> "tomorrow"
            else -> SimpleDateFormat("EEE d MMM", locale).apply { timeZone = zone }.format(hit.begin)
        }.lowercase(locale)
        if (hit.allDay) return if (day == "now") "today, all day" else "$day, all day"
        if (day == "now") return "now until ${time(hit.end, zone, locale, is24)}"
        return "$day ${time(hit.begin, zone, locale, is24)}"
    }

    private fun time(millis: Long, zone: TimeZone, locale: Locale, is24: Boolean) =
        SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", locale).apply { timeZone = zone }.format(millis).lowercase(locale)

    private fun daysBetween(from: Long, to: Long, zone: TimeZone): Int {
        fun midnight(t: Long) = Calendar.getInstance(zone).apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((midnight(to) - midnight(from)) / TimeUnit.DAYS.toMillis(1).toDouble()).toInt()
    }

    /** Opens the event in the calendar app. */
    fun openIntent(hit: CalendarHit): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, hit.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, hit.rawBegin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, hit.rawEnd)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, hit.allDay)
}

/** Reads upcoming events. A seam so the provider is tested without a calendar. */
internal fun interface CalendarEventsSource {
    suspend fun upcoming(context: Context, now: Long): List<CalendarHit>
}

internal object SystemCalendarEvents : CalendarEventsSource {
    override suspend fun upcoming(context: Context, now: Long): List<CalendarHit> = withContext(Dispatchers.IO) {
        runCatching { query(context, now) }.getOrDefault(emptyList())
    }

    private fun query(context: Context, now: Long): List<CalendarHit> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + CalendarMatcher.HORIZON_MILLIS)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = ArrayList<CalendarHit>()
        context.contentResolver.query(
            uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_EVENTS) {
                val allDay = c.getInt(4) == 1
                val begin = c.getLong(2)
                val end = c.getLong(3)
                // All-day instances are stored in UTC; shift them to local midnight, as the calendar tile does.
                val offset = if (allDay) -TimeZone.getDefault().getOffset(begin).toLong() else 0L
                out += CalendarHit(
                    eventId = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)",
                    begin = begin + offset, end = end + offset, rawBegin = begin, rawEnd = end,
                    allDay = allDay,
                    location = c.getString(5)?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out.filter { it.end > now }
    }

    private const val MAX_EVENTS = 1000
}

/** Feature 6: upcoming calendar events whose title or place matches. */
internal class CalendarProvider(
    private val source: CalendarEventsSource = SystemCalendarEvents,
    private val hasPermission: (Context) -> Boolean = { it.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
    private val is24Hour: (Context) -> Boolean = { android.text.format.DateFormat.is24HourFormat(it) },
    private val zone: () -> TimeZone = TimeZone::getDefault,
) : SearchProvider {
    override val id = "calendar"
    override val feature = SmartFeature.CALENDAR
    override val section = "calendar"
    override val debounceMillis = 100L

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        if (!hasPermission(ctx.context)) return emptyList()
        val now = ctx.now
        val is24 = is24Hour(ctx.context)
        return CalendarMatcher.top(source.upcoming(ctx.context, now), query, MAX_ROWS).map { hit ->
            val whenText = CalendarMatcher.whenText(hit, now, zone(), ctx.locale, is24)
            SearchItem(
                key = "e${hit.eventId}@${hit.rawBegin}",
                title = hit.title,
                subtitle = listOfNotNull(whenText, hit.location).joinToString(" · "),
                icon = SearchGlyphs.calendar,
                action = SearchActions.start(CalendarMatcher.openIntent(hit)),
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 3
    }
}
