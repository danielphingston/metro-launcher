package io.github.danielphingston.metro.ui.search

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Dates asked in words: "days till nov 3rd", "how many days since jan 1", "what day is it in 10 days",
 * "2 weeks from today", "date after 3 months", "next friday", "what day is christmas". The answer is
 * the weekday and full date in [Locale] form, with how far it is from today. The words are English;
 * numeric dates such as 3/11 are left alone, as they also read as a sum.
 */
object DateAnswers {
    fun answer(input: String, today: LocalDate, locale: Locale = Locale.getDefault()): Answer? {
        val shown = input.trim()
        if (shown.isEmpty() || shown.length > MAX_LENGTH) return null
        val text = shown.lowercase(Locale.ROOT).replace('’', '\'').trimEnd('?', '.', '!', ' ').replace(spaces, " ")
        val result = span(text, today, locale) ?: point(text, today, locale) ?: return null
        return Answer(result, shown)
    }

    private const val MAX_LENGTH = 80
    private val spaces = Regex("""\s+""")

    /** "days till nov 3": how long from today to a date, or since one. */
    private fun span(text: String, today: LocalDate, locale: Locale): String? {
        val match = spanPattern.matchEntire(text) ?: return null
        // "since jan 1" alone isn't asking anything: a unit or "how long" makes it a question.
        if (match.groupValues[1].isEmpty() && !text.startsWith("how long")) return null
        val unit = match.groupValues[1].ifEmpty { "days" }
        val since = match.groupValues[2] == "since"
        val target = pointOf(match.groupValues[3], today, past = since)?.date ?: return null
        val days = ChronoUnit.DAYS.between(today, target)
        val date = full(target, locale)
        if (days == 0L) return "Today · $date"
        val (from, to) = if (target.isBefore(today)) target to today else today to target
        val amount = when {
            unit.startsWith("week") -> weeksAndDays(Math.abs(days))
            unit.startsWith("month") -> monthsAndDays(from, to)
            else -> plural(Math.abs(days), "day") + if (Math.abs(days) >= 14) " (${weeksAndDays(Math.abs(days))})" else ""
        }
        val ago = if (days < 0 && !since) " ago" else ""
        return "$amount$ago · $date"
    }

    /** A single date: "in 10 days", "next friday", "what day is nov 3", "tomorrow". */
    private fun point(text: String, today: LocalDate, locale: Locale): String? {
        val direct = offsetOf(text, today) ?: pointOf(text, today, past = false)
        if (direct != null && direct.bare) return show(direct, today, locale)
        var rest = text
        var asked = false
        for (prefix in questionPrefixes) {
            val match = prefix.find(rest) ?: continue
            rest = rest.substring(match.range.last + 1).trim()
            asked = true
        }
        rest = rest.removeSuffix(" date").removeSuffix(" be").trim()
        if (asked && (rest.isEmpty() || rest == "it")) rest = "today"
        if (!asked) return null
        return show(offsetOf(rest, today) ?: pointOf(rest, today, past = false) ?: return null, today, locale)
    }

    private fun show(p: Point, today: LocalDate, locale: Locale): String {
        val date = full(p.date, locale)
        return if (p.relative) "$date · ${relative(ChronoUnit.DAYS.between(today, p.date))}" else date
    }

    /** A resolved date; [relative] when "in 4 days" adds something, [bare] when it may answer without a question around it. */
    private class Point(val date: LocalDate, val relative: Boolean, val bare: Boolean)

    /** "in 10 days", "2 weeks from now", "3 months ago", "10 days after nov 3". */
    private fun offsetOf(text: String, today: LocalDate): Point? {
        val match = offsetPattern.matchEntire(text) ?: return null
        val lead = match.groupValues[1]
        val count = number(match.groupValues[2]) ?: return null
        val unit = match.groupValues[3]
        val tail = match.groupValues[4]
        val anchorText = match.groupValues.subList(5, 8).firstOrNull { it.isNotEmpty() }.orEmpty()
        if (lead.isEmpty() && tail.isEmpty()) return null // "10 days" alone is a length, not a date
        val anchor = if (anchorText.isEmpty()) today else pointOf(anchorText, today, past = false)?.date ?: return null
        val back = tail in setOf("ago", "back", "before", "earlier") || tail.startsWith("before ")
        val signed = if (back) -count else count
        val date = try {
            when {
                unit.startsWith("day") -> anchor.plusDays(signed)
                unit.startsWith("week") -> anchor.plusWeeks(signed)
                unit.startsWith("fortnight") -> anchor.plusWeeks(2 * signed)
                unit.startsWith("month") -> anchor.plusMonths(signed)
                else -> anchor.plusYears(signed)
            }
        } catch (_: DateTimeException) {
            return null
        }
        return Point(date, relative = anchorText.isNotEmpty() || !unit.startsWith("day"), bare = true)
    }

    /** A named day: today, tomorrow, (next) friday, christmas, "nov 3rd", "3 november 2027". [past] picks last year's when no year is given. */
    private fun pointOf(text: String, today: LocalDate, past: Boolean): Point? {
        when (text) {
            "today", "now", "tonight", "today's", "todays" -> return Point(today, relative = false, bare = false)
            "tomorrow", "tmrw", "tmr" -> return Point(today.plusDays(1), relative = false, bare = true)
            "yesterday" -> return Point(today.minusDays(1), relative = false, bare = true)
            "day after tomorrow", "the day after tomorrow" -> return Point(today.plusDays(2), relative = false, bare = true)
            "day before yesterday", "the day before yesterday" -> return Point(today.minusDays(2), relative = false, bare = true)
        }
        weekdayPattern.matchEntire(text)?.let { match ->
            val day = weekdays[match.groupValues[2]] ?: return@let
            val date = when (match.groupValues[1]) {
                "next" -> today.with(TemporalAdjusters.next(day))
                "last", "previous" -> today.with(TemporalAdjusters.previous(day))
                else -> if (past) today.with(TemporalAdjusters.previousOrSame(day)) else today.with(TemporalAdjusters.nextOrSame(day))
            }
            return Point(date, relative = true, bare = match.groupValues[1].isNotEmpty())
        }
        holidays[text.removePrefix("the ")]?.let { (month, day) -> return yearly(month, day, null, today, past)?.let { Point(it, true, bare = false) } }
        val (month, day, year) = monthFirst.matchEntire(text)?.let { m -> Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
            ?: dayFirst.matchEntire(text)?.let { m -> Triple(m.groupValues[2], m.groupValues[1], m.groupValues[3]) }
            ?: return null
        val monthNumber = months.entries.firstOrNull { (name, _) -> month == name || (month.length >= 3 && name.startsWith(month)) }?.value
            ?: return null
        val date = yearly(monthNumber, day.toInt(), year.toIntOrNull(), today, past) ?: return null
        return Point(date, relative = true, bare = true)
    }

    /** [month]/[day] in [year], or else in the next (or with [past] the last) year it falls on. */
    private fun yearly(month: Int, day: Int, year: Int?, today: LocalDate, past: Boolean): LocalDate? {
        fun of(y: Int) = try { LocalDate.of(y, month, day) } catch (_: DateTimeException) { null }
        if (year != null) return of(year)
        val step = if (past) -1 else 1
        // Up to 8 years on, so 29 February finds its next leap year.
        return (0..8).asSequence().mapNotNull { of(today.year + it * step) }
            .firstOrNull { if (past) !it.isAfter(today) else !it.isBefore(today) }
    }

    private fun full(date: LocalDate, locale: Locale) = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))

    private fun relative(days: Long) = when {
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days == -1L -> "yesterday"
        days > 0 -> "in ${plural(days, "day")}"
        else -> "${plural(-days, "day")} ago"
    }

    private fun weeksAndDays(days: Long): String {
        val weeks = days / 7
        val rest = days % 7
        return listOfNotNull(plural(weeks, "week").takeIf { weeks > 0 }, plural(rest, "day").takeIf { rest > 0 || weeks == 0L }).joinToString(", ")
    }

    private fun monthsAndDays(from: LocalDate, to: LocalDate): String {
        val period = Period.between(from, to)
        val months = period.toTotalMonths()
        val days = period.days.toLong()
        return listOfNotNull(plural(months, "month").takeIf { months > 0 }, plural(days, "day").takeIf { days > 0 || months == 0L }).joinToString(", ")
    }

    private fun plural(n: Long, word: String) = if (n == 1L) "1 $word" else "$n ${word}s"

    private fun number(text: String): Long? = text.toLongOrNull()?.takeIf { it <= 100_000 } ?: numberWords[text]

    private val numberWords = mapOf(
        "a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
        "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "a couple of" to 2L, "a few" to 3L,
    )

    private val months = linkedMapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
    )

    private val weekdays = DayOfWeek.entries.flatMap { day ->
        val name = day.name.lowercase(Locale.ROOT)
        listOf(name to day, name.take(3) to day)
    }.toMap() + mapOf("tues" to DayOfWeek.TUESDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "weds" to DayOfWeek.WEDNESDAY)

    private val holidays = mapOf(
        "christmas" to (12 to 25), "christmas day" to (12 to 25), "xmas" to (12 to 25), "christmas eve" to (12 to 24),
        "new year" to (1 to 1), "new years" to (1 to 1), "new year's" to (1 to 1), "new year's day" to (1 to 1), "new years day" to (1 to 1),
        "new year's eve" to (12 to 31), "new years eve" to (12 to 31), "halloween" to (10 to 31), "valentine's day" to (2 to 14), "valentines day" to (2 to 14),
    )

    private const val MONTH = """([a-z]{3,9})\.?"""
    private const val DAY = """(\d{1,2})(?:st|nd|rd|th)?"""
    private const val YEAR = """(?:,?\s+(\d{4}))?"""
    private val monthFirst = Regex("""^(?:the\s+)?$MONTH\s+(?:the\s+)?$DAY$YEAR$""")
    private val dayFirst = Regex("""^(?:the\s+)?$DAY\s+(?:of\s+)?$MONTH$YEAR$""")
    private val weekdayPattern = Regex("""^(?:(next|this|coming|last|previous)\s+)?([a-z]{3,9})$""")

    private val spanPattern = Regex(
        """^(?:how\s+many\s+|no\.?\s+of\s+|number\s+of\s+|how\s+long\s+)?(days?|weeks?|months?)?\s*(?:are\s+)?(?:left\s+|remaining\s+|to\s+go\s+)?""" +
            """(till|until|untill|til|to|before|for|since)\s+(.+)$""",
    )

    private val offsetPattern = Regex(
        """^(in|after|within)?\s*(\d{1,6}|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|a couple of|a few)\s+""" +
            """(days?|weeks?|fortnights?|months?|years?)\s*""" +
            """(from now|from today|later|after|hence|ago|back|before|earlier|from (.+)|after (.+)|before (.+))?$""",
    )

    private val questionPrefixes = listOf(
        Regex("""^(?:what|which)(?:'s|\s+is|\s+was|\s+will)?(?:\s+the)?\s+(?:date|day|weekday|day of the week)""" +
            """(?:\s+(?:is it|will it be|was it|it will be|would it be|is|was|will be|would be|falls on|is on|be))?(?:\s+on)?(?=\s|$)"""),
        Regex("""^when\s+is(?=\s)"""),
        Regex("""^(?:date|day|weekday)(?:\s+(?:of|on))?(?=\s)"""),
        Regex("""^what(?:'s|\s+is)(?=\s)"""),
    )
}
