package io.github.danielphingston.metro.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The routine tile's rules, pure so they can be tested: what it shows at a moment and what a tap
 * does. A daily routine has up to [MAX_TIMES] doses; each one is due from its time, and they are
 * ticked in order, so the count of today's marks says which are done. An "every few days" routine
 * is due once that many days have passed since it was last done.
 */
object Routines {
    const val MAX_TIMES = 6
    const val MAX_EVERY_DAYS = 365

    /** Marks older than this go, except the newest, which an "every few days" routine counts from. */
    const val KEEP_DAYS = 35L

    /** How long a second tap takes back the first; the tile says so while it can. */
    const val UNDO_MILLIS = 5_000L

    /** Quick choices for a dose time, in minutes after midnight. */
    val PRESETS = listOf("morning" to 8 * 60, "noon" to 12 * 60, "evening" to 19 * 60, "night" to 22 * 60)

    data class Slot(val minutes: Int, val done: Boolean, val due: Boolean)

    data class Status(
        /** Something is waiting to be done: the tile shows in the accent colour. */
        val due: Boolean,
        /** Days overdue (every few days only), else 0. */
        val late: Int,
        /** Nothing more to do today. */
        val complete: Boolean,
        val headline: String,
        val detail: String,
        /** Daily: the doses in order. Empty for every few days. */
        val slots: List<Slot>,
        /** Every few days: how far into the wait it is, 0..1. */
        val progress: Float?,
    )

    fun status(r: TileContent.Routine, now: Long, zone: ZoneId, is24Hour: Boolean): Status {
        val today = dateOf(now, zone)
        return if (r.daily && r.times.isNotEmpty()) {
            val minute = minuteOf(now, zone)
            val done = r.marks.count { dateOf(it, zone) == today }
            val total = r.times.size
            val dueCount = r.times.count { it <= minute }
            val complete = done >= total
            val due = done < dueCount
            val headline = when {
                complete -> "all done"
                due && dueCount - done > 1 -> "${dueCount - done} due"
                due -> "due now"
                else -> "next ${timeLabel(r.times[done], is24Hour)}"
            }
            val slots = r.times.mapIndexed { i, t -> Slot(t, i < done, t <= minute) }
            Status(due, 0, complete, headline, "${done.coerceAtMost(total)} of $total today", slots, null)
        } else {
            val every = r.everyDays.coerceIn(1, MAX_EVERY_DAYS)
            val last = r.marks.maxOrNull()
            if (last == null) return Status(true, 0, false, "not done yet", everyText(every), emptyList(), 1f)
            val since = (today.toEpochDay() - dateOf(last, zone).toEpochDay()).toInt().coerceAtLeast(0)
            val left = every - since
            val headline = when {
                since == 0 -> "done"
                left > 1 -> "in $left days"
                left == 1 -> "tomorrow"
                left == 0 -> "due today"
                else -> "${plural(-left, "day")} late"
            }
            val detail = when (since) {
                0 -> if (every == 1) "again tomorrow" else "again in $every days"
                1 -> "done yesterday"
                else -> "done $since days ago"
            }
            Status(since > 0 && left <= 0, (-left).coerceAtLeast(0), since == 0, headline, detail, emptyList(), (since.toFloat() / every).coerceIn(0f, 1f))
        }
    }

    /** When [status] next changes by itself: the next dose time today, else the next midnight. */
    fun nextChange(r: TileContent.Routine, now: Long, zone: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        val midnight = zoned.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (!r.daily) return midnight
        val minute = zoned.hour * 60 + zoned.minute
        val next = r.times.firstOrNull { it > minute } ?: return midnight
        return zoned.toLocalDate().atStartOfDay(zone).plusMinutes(next.toLong()).toInstant().toEpochMilli()
    }

    /** Whether a tap can tick something now: a dose left today, or not yet done today. */
    fun canMark(r: TileContent.Routine, now: Long, zone: ZoneId): Boolean {
        val today = dateOf(now, zone)
        val done = r.marks.count { dateOf(it, zone) == today }
        return if (r.daily && r.times.isNotEmpty()) done < r.times.size else done == 0
    }

    /** [r] marked done at [now], with marks older than [KEEP_DAYS] dropped (the newest always stays). */
    fun mark(r: TileContent.Routine, now: Long): TileContent.Routine = r.copy(marks = prune(r.marks + now, now))

    /** [r] without the mark made at [at]. */
    fun unmark(r: TileContent.Routine, at: Long): TileContent.Routine = r.copy(marks = r.marks - at)

    /** [r] with today's marks set to [count] (the customise sheet's correction), keeping the earliest. */
    fun setToday(r: TileContent.Routine, count: Int, now: Long, zone: ZoneId): TileContent.Routine {
        val today = dateOf(now, zone)
        val todays = r.marks.filter { dateOf(it, zone) == today }.sorted()
        val others = r.marks.filter { dateOf(it, zone) != today }
        val kept = todays.take(count) + List((count - todays.size).coerceAtLeast(0)) { now }
        return r.copy(marks = prune((others + kept).sorted(), now))
    }

    /** How many marks [r] has today. */
    fun doneToday(r: TileContent.Routine, now: Long, zone: ZoneId): Int {
        val today = dateOf(now, zone)
        return r.marks.count { dateOf(it, zone) == today }
    }

    /** Whether [r] was done on the day before [now]'s. */
    fun doneYesterday(r: TileContent.Routine, now: Long, zone: ZoneId): Boolean {
        val yesterday = dateOf(now, zone).minusDays(1)
        return r.marks.any { dateOf(it, zone) == yesterday }
    }

    /** [r] with yesterday marked done (at noon) or not. */
    fun setYesterday(r: TileContent.Routine, done: Boolean, now: Long, zone: ZoneId): TileContent.Routine {
        val yesterday = dateOf(now, zone).minusDays(1)
        val without = r.marks.filter { dateOf(it, zone) != yesterday }
        val noon = yesterday.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        return r.copy(marks = prune((if (done) without + noon else without).sorted(), now))
    }

    /** How much of each of the last [days] days (oldest first, today last) was done, 0..1. */
    fun history(r: TileContent.Routine, now: Long, zone: ZoneId, days: Int): List<Float> {
        val today = dateOf(now, zone)
        val perDay = r.marks.groupingBy { dateOf(it, zone) }.eachCount()
        val target = if (r.daily && r.times.isNotEmpty()) r.times.size else 1
        return (days - 1 downTo 0).map { back ->
            ((perDay[today.minusDays(back.toLong())] ?: 0).toFloat() / target).coerceIn(0f, 1f)
        }
    }

    /** A routine time: "8 am", "12:30 pm", or "08:00" with a 24-hour clock. */
    fun timeLabel(minutes: Int, is24Hour: Boolean): String {
        val h = minutes / 60
        val m = minutes % 60
        if (is24Hour) return "%02d:%02d".format(h, m)
        val hour = if (h % 12 == 0) 12 else h % 12
        val suffix = if (h < 12) "am" else "pm"
        return if (m == 0) "$hour $suffix" else "$hour:%02d $suffix".format(m)
    }

    /** [times] with [minutes] added (sorted, no repeats, at most [MAX_TIMES]). */
    fun addTime(times: List<Int>, minutes: Int): List<Int> =
        if (minutes in times || times.size >= MAX_TIMES) times else (times + minutes.coerceIn(0, 24 * 60 - 1)).sorted()

    fun everyText(days: Int): String = if (days == 1) "every day" else "every $days days"

    /** The routine a new tile starts with: once a day, in the morning. */
    fun new(now: Long) = TileContent.Routine("routine", daily = true, times = listOf(8 * 60), everyDays = 7, created = now)

    private fun prune(marks: List<Long>, now: Long): List<Long> {
        val newest = marks.maxOrNull() ?: return marks
        val cutoff = now - KEEP_DAYS * 86_400_000L
        return marks.filter { it >= cutoff || it == newest }.takeLast(MAX_TIMES * KEEP_DAYS.toInt())
    }

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun minuteOf(millis: Long, zone: ZoneId): Int = Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }

    internal fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"
}

/**
 * What a tap on a routine tile does: tick it, take back the tick made moments ago (a second tap
 * within [Routines.UNDO_MILLIS], which the tile announces), or nothing when all is done.
 */
object RoutineTap {
    sealed interface Outcome {
        val hint: String

        data class Marked(val routine: TileContent.Routine, val at: Long, override val hint: String) : Outcome

        data class Unmarked(val routine: TileContent.Routine, override val hint: String) : Outcome

        data class Nothing(override val hint: String) : Outcome
    }

    /** [lastMark] is the mark the previous tap made and when, if it is still there to take back. */
    fun tap(r: TileContent.Routine, now: Long, zone: ZoneId, lastMark: Long?): Outcome {
        if (lastMark != null && lastMark in r.marks && now - lastMark in 0..Routines.UNDO_MILLIS) {
            return Outcome.Unmarked(Routines.unmark(r, lastMark), "taken back")
        }
        if (!Routines.canMark(r, now, zone)) {
            return Outcome.Nothing(if (r.daily && r.times.isNotEmpty()) "all done today" else "already done today")
        }
        return Outcome.Marked(Routines.mark(r, now), now, "done · tap again to undo")
    }
}

/** The countdown tile's numbers. */
object Countdowns {
    /** Days from [now]'s date to [epochDay]: negative once it has passed. */
    fun daysLeft(epochDay: Long, now: Long, zone: ZoneId): Long =
        epochDay - Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()

    /** The next midnight after [now], when [daysLeft] changes. */
    fun nextChange(now: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The big number and the words under it, in one of three [view]s: days, weeks and days, the date. */
    fun face(epochDay: Long, now: Long, zone: ZoneId, view: Int): Pair<String, String> {
        val days = daysLeft(epochDay, now, zone)
        val date = LocalDate.ofEpochDay(epochDay)
        return when {
            days == 0L -> "today" to ""
            view % 3 == 2 -> "${date.dayOfMonth} ${month(date)}" to date.dayOfWeek.name.take(3).lowercase()
            view % 3 == 1 && abs(days) >= 7 -> "${abs(days) / 7}w ${abs(days) % 7}d" to if (days > 0) "to go" else "ago"
            days > 0 -> "$days" to if (days == 1L) "day to go" else "days to go"
            else -> "${-days}" to if (days == -1L) "day ago" else "days ago"
        }
    }

    fun dateLabel(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.dayOfWeek.name.take(3).lowercase()} ${date.dayOfMonth} ${month(date)} ${date.year}"
    }

    private fun month(date: LocalDate) = date.month.name.take(3).lowercase()
}

/** One point of handwriting: [x] and [y] as fractions of the drawing's width, [pressure] 0..1. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float)

/**
 * Handwriting as a compact string: per stroke a point count, then 5 bytes a point (x and y to
 * 1/[SCALE] of the width, pressure to 1/255), Base64. A note of a few lines is 10 to 30 KB.
 * Anything unreadable decodes as no strokes.
 */
object InkCodec {
    private const val SCALE = 4000f
    private const val VERSION: Byte = 1

    /** The most points a note keeps; strokes past it are dropped so the layout stays small. */
    const val MAX_POINTS = 12_000

    fun encode(strokes: List<List<InkPoint>>): String {
        if (strokes.isEmpty()) return ""
        var total = 0
        val kept = strokes.filter { it.isNotEmpty() }.takeWhile { stroke -> (total + stroke.size <= MAX_POINTS).also { if (it) total += stroke.size } }
        val buffer = ByteBuffer.allocate(1 + kept.sumOf { 2 + it.size.coerceAtMost(65_535) * 5 })
        buffer.put(VERSION)
        kept.forEach { stroke ->
            val points = stroke.take(65_535)
            buffer.putShort(points.size.toShort())
            points.forEach {
                buffer.putShort((it.x * SCALE).roundToInt().coerceIn(0, 65_535).toShort())
                buffer.putShort((it.y * SCALE).roundToInt().coerceIn(0, 65_535).toShort())
                buffer.put((it.pressure * 255).roundToInt().coerceIn(0, 255).toByte())
            }
        }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decode(encoded: String): List<List<InkPoint>> {
        if (encoded.isEmpty()) return emptyList()
        return runCatching {
            val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded))
            if (buffer.get() != VERSION) return emptyList()
            val strokes = ArrayList<List<InkPoint>>()
            while (buffer.remaining() >= 2) {
                val count = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < count * 5) break
                strokes += List(count) {
                    InkPoint(
                        (buffer.getShort().toInt() and 0xFFFF) / SCALE,
                        (buffer.getShort().toInt() and 0xFFFF) / SCALE,
                        (buffer.get().toInt() and 0xFF) / 255f,
                    )
                }
            }
            strokes
        }.getOrDefault(emptyList())
    }

    /** [stroke] without points closer than [minDistance] to the one kept before them (the last always stays). */
    fun simplify(stroke: List<InkPoint>, minDistance: Float = 0.002f): List<InkPoint> {
        if (stroke.size < 3) return stroke
        val out = ArrayList<InkPoint>(stroke.size)
        out += stroke.first()
        for (i in 1 until stroke.size - 1) {
            val p = stroke[i]
            val q = out.last()
            if (abs(p.x - q.x) + abs(p.y - q.y) >= minDistance) out += p
        }
        out += stroke.last()
        return out
    }
}

/** How tool tiles are saved in the layout's JSON; see [TileJson]. */
internal object ToolJson {
    val TYPES = setOf("routine", "countdown", "coin", "dice", "notes")

    fun encode(c: TileContent.Tool, obj: JSONObject): JSONObject = when (c) {
        is TileContent.Routine -> obj.put("type", "routine").put("name", c.name).put("daily", c.daily)
            .put("times", JSONArray(c.times)).put("everyDays", c.everyDays).put("marks", JSONArray(c.marks)).put("created", c.created)
        is TileContent.Countdown -> obj.put("type", "countdown").put("name", c.name).put("day", c.epochDay).put("created", c.created)
        is TileContent.Coin -> obj.put("type", "coin").put("created", c.created)
        is TileContent.Dice -> obj.put("type", "dice").put("count", c.count).put("sides", c.sides).put("created", c.created)
        is TileContent.Notes -> obj.put("type", "notes").put("name", c.name).put("style", c.style.id)
            .put("items", JSONArray().apply { c.items.forEach { put(JSONObject().put("t", it.text).put("d", it.done)) } })
            .put("text", c.text).put("ink", c.ink).put("created", c.created)
    }

    fun decode(obj: JSONObject): TileContent.Tool? = decodeRaw(obj)?.let(ToolLimits::clean)

    private fun decodeRaw(obj: JSONObject): TileContent.Tool? = when (obj.optString("type")) {
        "routine" -> TileContent.Routine(
            name = obj.optString("name"),
            daily = obj.optBoolean("daily", true),
            times = ints(obj.optJSONArray("times")).filter { it in 0 until 24 * 60 }.distinct().sorted().take(Routines.MAX_TIMES),
            everyDays = obj.optInt("everyDays", 7).coerceIn(1, Routines.MAX_EVERY_DAYS),
            marks = longs(obj.optJSONArray("marks")).sorted(),
            created = obj.optLong("created"),
        )
        "countdown" -> TileContent.Countdown(obj.optString("name"), obj.getLong("day"), obj.optLong("created"))
        "coin" -> TileContent.Coin(obj.optLong("created"))
        "dice" -> TileContent.Dice(obj.optInt("count", 1).coerceIn(1, 3), obj.optInt("sides", 6).takeIf { it in DICE_SIDES } ?: 6, obj.optLong("created"))
        "notes" -> TileContent.Notes(
            name = obj.optString("name"),
            style = NoteStyle.fromId(obj.optString("style")),
            items = obj.optJSONArray("items")?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { NoteItem(it.optString("t"), it.optBoolean("d")) }
            }.orEmpty(),
            text = obj.optString("text"),
            ink = obj.optString("ink"),
            created = obj.optLong("created"),
        )
        else -> null
    }

    val DICE_SIDES = listOf(6, 20)

    private fun ints(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { (array?.opt(it) as? Number)?.toInt() }

    private fun longs(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { (array?.opt(it) as? Number)?.toLong() }
}

/**
 * Undo puts back whole layouts (see [TileUndo]). What was done with a tool tile since (a tick, a
 * list item, a note) is not an edit of the layout, so it is carried into the restored layout.
 */
internal object ToolCarry {
    fun carry(restored: List<TileSpec>, current: List<TileSpec>): List<TileSpec> {
        val live = HashMap<Long, TileContent.Tool>()
        fun collect(list: List<TileSpec>): Unit = list.forEach { spec ->
            when (val c = spec.content) {
                is TileContent.Tool -> live[spec.id] = c
                is TileContent.Folder -> collect(c.children)
                else -> Unit
            }
        }
        collect(current)
        if (live.isEmpty()) return restored
        fun apply(list: List<TileSpec>): List<TileSpec> = list.map { spec ->
            when (val c = spec.content) {
                is TileContent.Routine -> (live[spec.id] as? TileContent.Routine)?.let { spec.copy(content = c.copy(marks = it.marks)) } ?: spec
                is TileContent.Notes -> (live[spec.id] as? TileContent.Notes)?.let { spec.copy(content = it) } ?: spec
                is TileContent.Folder -> spec.copy(content = c.copy(children = apply(c.children)))
                else -> spec
            }
        }
        return apply(restored)
    }
}

/** The names tool tiles go by when they have none of their own. */
object ToolLabels {
    fun label(c: TileContent.Tool): String = when (c) {
        is TileContent.Routine -> c.name.ifBlank { "routine" }
        is TileContent.Countdown -> c.name.ifBlank { "countdown" }
        is TileContent.Coin -> "coin"
        is TileContent.Dice -> "dice"
        is TileContent.Notes -> c.name.ifBlank { c.style.label }
    }
}

/**
 * How much a tool tile holds, so no tile, open band or saved layout grows without end: names fit
 * a tile's label, list items a line, a note a few screens. Everything stored passes [clean]
 * (typing, saving, loading a backup); the fields also stop at these as you type.
 */
object ToolLimits {
    const val NAME = 40
    const val ITEM = 100
    const val ITEMS = 50
    const val TEXT = 2_000

    fun clean(c: TileContent.Tool): TileContent.Tool = when (c) {
        is TileContent.Routine -> c.copy(
            name = c.name.take(NAME),
            times = c.times.filter { it in 0 until 24 * 60 }.distinct().sorted().take(Routines.MAX_TIMES),
            everyDays = c.everyDays.coerceIn(1, Routines.MAX_EVERY_DAYS),
        )
        is TileContent.Countdown -> c.copy(name = c.name.take(NAME))
        is TileContent.Coin -> c
        is TileContent.Dice -> c.copy(count = c.count.coerceIn(1, 3), sides = c.sides.takeIf { it in ToolJson.DICE_SIDES } ?: 6)
        is TileContent.Notes -> c.copy(
            name = c.name.take(NAME),
            items = c.items.take(ITEMS).map { it.copy(text = it.text.take(ITEM)) },
            text = c.text.take(TEXT),
        )
    }
}
