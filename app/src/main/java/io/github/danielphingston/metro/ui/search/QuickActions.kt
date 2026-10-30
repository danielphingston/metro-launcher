package io.github.danielphingston.metro.ui.search

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import io.github.danielphingston.metro.data.SmartFeature
import java.util.Locale

/** What a quick-action phrase asks for. */
internal sealed interface QuickAction {
    data class Timer(val seconds: Int, val label: String? = null) : QuickAction
    data class Alarm(val hour: Int, val minute: Int, val label: String? = null) : QuickAction
    data class Note(val text: String) : QuickAction

    /** "call mom" / "message mom": answered by the contacts feature, see [PersonActions]. */
    sealed interface Person : QuickAction {
        val name: String
    }

    data class Call(override val name: String) : Person
    data class Message(override val name: String) : Person
}

/** Reads quick-action phrases; pure, so every accepted and rejected form is in the tests. */
internal object QuickActionParser {
    private val timerLead = Regex("""^(?:set\s+(?:an?\s+)?|start\s+(?:an?\s+)?)?timer(?:\s+for)?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val timerTrail = Regex("""^(.+?)\s+timer$""", RegexOption.IGNORE_CASE)
    private val alarmLead = Regex("""^(?:set\s+(?:an?\s+)?)?alarm(?:\s+(?:for|at))?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val noteLead = Regex("""^note[:\s]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val callLead = Regex("""^(?:call|phone|dial|ring)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val messageLead = Regex("""^(?:message|text|sms|msg)\s+(.+)$""", RegexOption.IGNORE_CASE)

    private val clock = Regex("""^(\d{1,2})(?:[:.h](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?![\p{L}\d])\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val compactClock = Regex("""^(\d{3,4})\s*(am|pm|a\.m\.|p\.m\.)?(?![\p{L}\d])\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val durationPart = Regex("""(\d+(?:[.,]\d+)?)\s*([a-z]*)""", RegexOption.IGNORE_CASE)
    private val colonDuration = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

    const val MAX_TIMER_SECONDS = 24 * 60 * 60

    fun parse(query: String): QuickAction? {
        val q = query.trim().replace(Regex("\\s+"), " ")
        timerLead.matchEntire(q)?.let { return timer(it.groupValues[1]) }
        timerTrail.matchEntire(q)?.let { return timer(it.groupValues[1]) }
        alarmLead.matchEntire(q)?.let { return alarm(it.groupValues[1]) }
        noteLead.matchEntire(q)?.let { return QuickAction.Note(it.groupValues[1].trim()) }
        callLead.matchEntire(q)?.let { return QuickAction.Call(it.groupValues[1].trim()) }
        messageLead.matchEntire(q)?.let { return QuickAction.Message(it.groupValues[1].trim()) }
        return null
    }

    /** "5 min", "90s", "1h30m", "1 hour 15 minutes", "1:30" (minutes and seconds), a bare "5" (minutes). */
    fun duration(text: String): Int? {
        val t = text.trim().lowercase(Locale.ROOT).removePrefix("for ").trim()
        colonDuration.matchEntire(t)?.let { m ->
            val (a, b, c) = m.destructured
            val seconds = if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
            return seconds.takeIf { it in 1..MAX_TIMER_SECONDS }
        }
        var total = 0.0
        var rest = t
        var smallerThan = Int.MAX_VALUE // the unit rank of the previous part, so "1h 30" reads 30 as minutes
        var parts = 0
        while (rest.isNotEmpty()) {
            val m = durationPart.find(rest)?.takeIf { it.range.first == 0 } ?: return null
            val number = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = m.groupValues[2]
            val (seconds, unitRank) = when {
                unit.isEmpty() -> when (smallerThan) { HOURS -> 60 to MINUTES; MINUTES -> 1 to SECONDS; else -> 60 to MINUTES }
                unit in setOf("h", "hr", "hrs", "hour", "hours") -> 3600 to HOURS
                unit in setOf("m", "min", "mins", "minute", "minutes") -> 60 to MINUTES
                unit in setOf("s", "sec", "secs", "second", "seconds") -> 1 to SECONDS
                else -> return null
            }
            if (unitRank >= smallerThan) return null
            total += number * seconds
            smallerThan = unitRank
            parts++
            rest = rest.substring(m.range.last + 1).trim().removePrefix("and").trim()
        }
        val whole = Math.round(total).toInt()
        return whole.takeIf { parts > 0 && it in 1..MAX_TIMER_SECONDS }
    }

    private const val HOURS = 3
    private const val MINUTES = 2
    private const val SECONDS = 1

    private fun timer(text: String): QuickAction? = duration(text)?.let { QuickAction.Timer(it) }

    private fun alarm(text: String): QuickAction? {
        val t = text.trim()
        var hour: Int
        var minute: Int
        var meridiem: String
        var label: String
        val m = clock.matchEntire(t)
        val compact = compactClock.matchEntire(t)
        when {
            // 730 or 1830: a clock written without a separator.
            compact != null -> {
                val digits = compact.groupValues[1]
                hour = digits.dropLast(2).toInt()
                minute = digits.takeLast(2).toInt()
                meridiem = compact.groupValues[2]
                label = compact.groupValues[3]
            }
            m != null -> {
                hour = m.groupValues[1].toInt()
                minute = m.groupValues[2].ifEmpty { "0" }.toInt()
                meridiem = m.groupValues[3]
                label = m.groupValues[4]
            }
            else -> return null
        }
        if (minute !in 0..59) return null
        val am = meridiem.lowercase(Locale.ROOT).startsWith("a")
        if (meridiem.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (am) 0 else 12
        } else if (hour !in 0..23) {
            return null
        }
        label = label.trim().removePrefix("for").trim().trim('-', ':', ' ')
        return QuickAction.Alarm(hour, minute, label.ifEmpty { null })
    }

    /** "5 minutes", "1 hour 30 minutes", "45 seconds". */
    fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h ${if (h == 1) "hour" else "hours"}" else null,
            if (m > 0) "$m ${if (m == 1) "minute" else "minutes"}" else null,
            if (s > 0) "$s ${if (s == 1) "second" else "seconds"}" else null,
        ).joinToString(" ")
    }

    /** "7:30" or "7:30 am", depending on the phone's clock style. */
    fun time(hour: Int, minute: Int, is24: Boolean): String {
        val mm = minute.toString().padStart(2, '0')
        if (is24) return "${hour.toString().padStart(2, '0')}:$mm"
        return "${if (hour % 12 == 0) 12 else hour % 12}:$mm ${if (hour < 12) "am" else "pm"}"
    }
}

/**
 * The hook for "call <name>" and "message <name>". Quick actions only recognise the phrase; the
 * contacts feature turns the name into rows (a contact with a call or message action). Without it
 * those phrases show nothing here.
 */
internal fun interface PersonActions {
    suspend fun items(action: QuickAction.Person, ctx: SearchContext): List<SearchItem>

    companion object {
        val None = PersonActions { _, _ -> emptyList() }
    }
}

/** Feature 3: timer, alarm and note from a typed phrase, as the top row. */
internal class QuickActionsProvider(
    private val personActions: PersonActions = PersonActions.None,
    private val resolves: (Context, Intent) -> Boolean = { context, intent -> context.packageManager.resolveActivity(intent, 0) != null },
    private val is24Hour: (Context) -> Boolean = { android.text.format.DateFormat.is24HourFormat(it) },
) : SearchProvider {
    override val id = "quick_actions"
    override val feature = SmartFeature.QUICK_ACTIONS
    override val placement = Placement.TOP

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val context = ctx.context
        return when (val action = QuickActionParser.parse(query) ?: return emptyList()) {
            is QuickAction.Timer -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (!resolves(context, intent)) return emptyList()
                listOf(item("timer", "start a ${QuickActionParser.describe(action.seconds)} timer", "clock app", SearchGlyphs.timer, intent))
            }
            is QuickAction.Alarm -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                action.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                if (!resolves(context, intent)) return emptyList()
                val time = QuickActionParser.time(action.hour, action.minute, is24Hour(context))
                listOf(item("alarm", "set an alarm for $time", action.label ?: "clock app", SearchGlyphs.alarm, intent))
            }
            is QuickAction.Note -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, action.text)
                if (!resolves(context, send)) return emptyList()
                listOf(item("note", "note: ${action.text}", "choose a notes app", SearchGlyphs.note, Intent.createChooser(send, "note")))
            }
            is QuickAction.Person -> personActions.items(action, ctx)
        }
    }

    private fun item(key: String, title: String, subtitle: String, icon: SearchIcon, intent: Intent) =
        SearchItem(key, title, subtitle, icon, SearchActions.start(intent), prominent = true)
}
