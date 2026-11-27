package io.github.danielphingston.metro.live.api

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns an API tile's templates into text. A template is plain text with formulas in braces:
 * "{driver.name} leads by {round(gap, 1)} s", "starts in {countdown(race.start)}". Behind an
 * interface so a sandboxed script engine could stand in later; [BuiltInFormulas] is the one there is.
 * Formulas only read the downloaded JSON and the clock: they can't reach the network, files or
 * any Android API, so a tile's setup is data, never code.
 */
interface ApiFormulas {
    /** [template] filled in from [root] (the downloaded JSON) at [now]; [row] is the list item a row template reads first. */
    fun evaluate(template: String, root: Any?, context: FormulaContext, row: Any? = NO_ROW): Evaluated

    /** Why [template] can't be read, or null when it can. */
    fun problem(template: String): String?

    companion object {
        /** Says "no list item": paths read the document itself. */
        val NO_ROW = Any()
    }
}

/** What a formula may know besides the JSON: the time, and how the phone writes times and numbers. */
data class FormulaContext(
    val now: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
    val is24Hour: Boolean = true,
)

/**
 * A template's text, and when it next changes by itself ([changesAt], epoch millis): a countdown
 * changes every second near the end and every minute or hour before that; text without time
 * functions never does (null). The tile redraws at that moment and not before, and only while it
 * is on screen: no network, no polling.
 */
data class Evaluated(val text: String, val changesAt: Long? = null, val error: String? = null)

class FormulaException(message: String) : Exception(message)

/**
 * The built-in formula language. Paths ("driver.name", "items[0].title", "$.meta.count", and
 * `["odd key"]`), "@" for the list item itself in a row, numbers, 'text' or "text", + - * / %, comparisons, ?? (a fallback when the left
 * side is missing), and the functions in [FUNCTIONS]. Inside a row template, paths read the list
 * item first and "$." reads the whole document. Bounded: templates up to [MAX_TEMPLATE] characters,
 * lists up to [MAX_LIST] items per function, [MAX_STEPS] steps per template.
 */
object BuiltInFormulas : ApiFormulas {
    const val MAX_TEMPLATE = 500
    const val MAX_LIST = 1000
    private const val MAX_STEPS = 20_000
    private const val MAX_DEPTH = 32
    private const val MAX_TEXT = 2_000

    /** The functions, with an example each, for the editor's list. */
    val FUNCTIONS: List<Pair<String, String>> = listOf(
        "upper" to "upper(name)",
        "lower" to "lower(name)",
        "title" to "title(name)",
        "trim" to "trim(name)",
        "left" to "left(name, 3)",
        "right" to "right(name, 3)",
        "replace" to "replace(name, '-', ' ')",
        "len" to "len(name)",
        "round" to "round(value, 1)",
        "floor" to "floor(value)",
        "ceil" to "ceil(value)",
        "abs" to "abs(value)",
        "min" to "min(a, b)",
        "max" to "max(a, b)",
        "fixed" to "fixed(value, 2)",
        "thousands" to "thousands(value)",
        "compact" to "compact(value)",
        "percent" to "percent(share, 0)",
        "convert" to "convert(temp, 'c', 'f')",
        "count" to "count(items)",
        "first" to "first(items)",
        "last" to "last(items)",
        "at" to "at(items, 2)",
        "join" to "join(items, ', ', 'name')",
        "sum" to "sum(items, 'points')",
        "avg" to "avg(items, 'points')",
        "countdown" to "countdown(race.start)",
        "until" to "until(race.start)",
        "ago" to "ago(updated)",
        "time" to "time(race.start)",
        "date" to "date(race.start, 'EEE d MMM')",
        "datetime" to "datetime(date + 'T' + time)",
        "if" to "if(live, 'live', 'next')",
        "default" to "default(name, 'none')",
        "number" to "number(text)",
        "text" to "text(value)",
    )

    private val NAMES = FUNCTIONS.map { it.first }.toSet()

    override fun evaluate(template: String, root: Any?, context: FormulaContext, row: Any?): Evaluated {
        if (template.length > MAX_TEMPLATE) return Evaluated("", error = "a template holds up to $MAX_TEMPLATE characters")
        val parts = try {
            split(template)
        } catch (e: FormulaException) {
            return Evaluated("", error = e.message)
        }
        val run = Run(root, row, context)
        val out = StringBuilder()
        var error: String? = null
        for (part in parts) {
            when (part) {
                is Part.Text -> out.append(part.text)
                is Part.Formula -> try {
                    out.append(show(run.eval(part.expr, 0), context))
                } catch (e: FormulaException) {
                    if (error == null) error = e.message
                } catch (e: RuntimeException) {
                    if (error == null) error = "can't work that out"
                }
            }
            if (out.length > MAX_TEXT) break
        }
        return Evaluated(out.take(MAX_TEXT).toString(), run.changesAt, error)
    }

    override fun problem(template: String): String? {
        if (template.length > MAX_TEMPLATE) return "a template holds up to $MAX_TEMPLATE characters"
        return try {
            split(template)
            null
        } catch (e: FormulaException) {
            e.message
        }
    }

    // ---- reading templates ----

    private sealed interface Part {
        data class Text(val text: String) : Part
        data class Formula(val expr: Expr) : Part
    }

    private fun split(template: String): List<Part> {
        val parts = ArrayList<Part>()
        val text = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && template.getOrNull(i + 1) == '{' -> { text.append('{'); i += 2 }
                c == '}' && template.getOrNull(i + 1) == '}' -> { text.append('}'); i += 2 }
                c == '{' -> {
                    val end = closing(template, i + 1)
                    if (text.isNotEmpty()) { parts += Part.Text(text.toString()); text.clear() }
                    val source = template.substring(i + 1, end)
                    if (source.isBlank()) throw FormulaException("empty braces: put a field or a formula in them")
                    parts += Part.Formula(Parser(source).parseAll())
                    i = end + 1
                }
                c == '}' -> throw FormulaException("a } without its {")
                else -> { text.append(c); i++ }
            }
        }
        if (text.isNotEmpty()) parts += Part.Text(text.toString())
        return parts
    }

    /** Where the formula opened before [from] ends, skipping braces inside quotes. */
    private fun closing(template: String, from: Int): Int {
        var quote: Char? = null
        var i = from
        while (i < template.length) {
            val c = template[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == null && c == '{' -> throw FormulaException("a { inside a formula: close the first one with }")
                quote == null && c == '}' -> return i
            }
            i++
        }
        throw FormulaException("a { without its }")
    }

    private sealed interface Expr
    private data class Num(val value: Double) : Expr
    private data class Str(val value: String) : Expr
    private data class Lit(val value: Any?) : Expr
    /** [fromRoot]: "$." paths read the whole document even inside a row; [fromRow]: "@" paths read only the list item. */
    private data class Path(val steps: List<Any>, val fromRoot: Boolean, val fromRow: Boolean = false) : Expr
    private data class Call(val name: String, val args: List<Expr>) : Expr

    /** Steps read from a function's result: first(items).name. */
    private data class Access(val base: Expr, val steps: List<Any>) : Expr
    private data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
    private data class Negate(val operand: Expr) : Expr

    private class Parser(val src: String) {
        var i = 0
        var depth = 0

        fun parseAll(): Expr {
            val e = expr()
            skip()
            if (i < src.length) throw FormulaException("didn't expect '${src[i]}' in {$src}")
            return e
        }

        private fun skip() { while (i < src.length && src[i].isWhitespace()) i++ }

        private fun peek(op: String): Boolean { skip(); return src.startsWith(op, i) }

        private fun take(op: String): Boolean = if (peek(op)) { i += op.length; true } else false

        private fun nested(block: () -> Expr): Expr {
            if (++depth > MAX_DEPTH) throw FormulaException("that formula is nested too deeply")
            try { return block() } finally { depth-- }
        }

        fun expr(): Expr = nested {
            var left = comparison()
            while (take("??")) left = Binary("??", left, comparison())
            left
        }

        private fun comparison(): Expr {
            val left = additive()
            for (op in listOf("==", "!=", "<=", ">=", "<", ">")) if (take(op)) return Binary(op, left, additive())
            return left
        }

        private fun additive(): Expr {
            var left = multiplicative()
            while (true) {
                left = when {
                    take("+") -> Binary("+", left, multiplicative())
                    take("-") -> Binary("-", left, multiplicative())
                    else -> return left
                }
            }
        }

        private fun multiplicative(): Expr {
            var left = unary()
            while (true) {
                left = when {
                    take("*") -> Binary("*", left, unary())
                    take("/") -> Binary("/", left, unary())
                    take("%") -> Binary("%", left, unary())
                    else -> return left
                }
            }
        }

        private fun unary(): Expr = if (take("-")) nested { Negate(unary()) } else primary()

        private fun primary(): Expr {
            skip()
            if (i >= src.length) throw FormulaException("{$src} ends too soon")
            val c = src[i]
            return when {
                c == '(' -> { i++; val e = expr(); if (!take(")")) throw FormulaException("a ( without its ) in {$src}"); e }
                c == '\'' || c == '"' -> Str(string())
                c.isDigit() || (c == '.' && src.getOrNull(i + 1)?.isDigit() == true) -> Num(number())
                c == '$' -> { i++; Path(steps(), fromRoot = true) }
                c == '@' -> { i++; Path(steps(), fromRoot = false, fromRow = true) }
                c.isLetter() || c == '_' -> {
                    val name = word()
                    when {
                        peek("(") -> {
                            i++
                            val call = Call(name.lowercase(Locale.ROOT).also(::checkName), args())
                            val after = steps()
                            if (after.isEmpty()) call else Access(call, after)
                        }
                        name == "true" -> Lit(true)
                        name == "false" -> Lit(false)
                        name == "null" -> Lit(null)
                        else -> Path(listOf<Any>(name) + steps(), fromRoot = false)
                    }
                }
                c == '[' -> Path(steps(), fromRoot = false)
                else -> throw FormulaException("didn't expect '$c' in {$src}")
            }
        }

        private fun checkName(name: String) {
            if (name !in NAMES) throw FormulaException("there's no function called $name")
        }

        private fun args(): List<Expr> {
            val args = ArrayList<Expr>()
            if (take(")")) return args
            while (true) {
                args += expr()
                if (take(")")) return args
                if (!take(",")) throw FormulaException("expected , or ) in {$src}")
                if (args.size > 8) throw FormulaException("too many values in one function")
            }
        }

        private fun word(): String {
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
            return src.substring(start, i)
        }

        /** ".name", "[0]" and "['key']" after a path's start. */
        private fun steps(): List<Any> {
            val steps = ArrayList<Any>()
            while (i < src.length) {
                when {
                    src[i] == '.' && src.getOrNull(i + 1)?.let { it.isLetter() || it == '_' || it.isDigit() } == true -> {
                        i++
                        if (src[i].isDigit()) steps += number().toInt() else steps += word()
                    }
                    src[i] == '[' -> {
                        i++
                        skip()
                        val step: Any = when {
                            i < src.length && (src[i] == '\'' || src[i] == '"') -> string()
                            i < src.length && src[i] == '-' -> { i++; -number().toInt() }
                            else -> number().toInt()
                        }
                        skip()
                        if (i >= src.length || src[i] != ']') throw FormulaException("a [ without its ] in {$src}")
                        i++
                        steps += step
                    }
                    else -> break
                }
                if (steps.size > 64) throw FormulaException("that path is too long")
            }
            return steps
        }

        private fun string(): String {
            val quote = src[i++]
            val out = StringBuilder()
            while (i < src.length && src[i] != quote) {
                if (src[i] == '\\' && i + 1 < src.length) i++
                out.append(src[i++])
            }
            if (i >= src.length) throw FormulaException("text without its closing quote in {$src}")
            i++
            return out.toString()
        }

        private fun number(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            return src.substring(start, i).toDoubleOrNull() ?: throw FormulaException("${src.substring(start, i)} isn't a number")
        }
    }

    // ---- working them out ----

    private class Run(val root: Any?, val row: Any?, val context: FormulaContext) {
        var steps = 0
        var changesAt: Long? = null

        fun changeAt(at: Long) {
            if (at <= context.now) return
            changesAt = changesAt?.let { minOf(it, at) } ?: at
        }

        fun eval(e: Expr, depth: Int): Any? {
            if (++steps > MAX_STEPS) throw FormulaException("that formula takes too long")
            return when (e) {
                is Num -> e.value
                is Str -> e.value
                is Lit -> e.value
                is Path -> path(e)
                is Negate -> -(num(eval(e.operand, depth + 1)) ?: throw FormulaException("- needs a number"))
                is Binary -> binary(e, depth)
                is Call -> call(e.name, e.args, depth)
                is Access -> ApiJson.at(eval(e.base, depth + 1), e.steps)
            }
        }

        private fun path(p: Path): Any? {
            if (p.fromRow) return if (row === ApiFormulas.NO_ROW) null else ApiJson.at(row, p.steps)
            if (!p.fromRoot && row !== ApiFormulas.NO_ROW) {
                val fromRow = ApiJson.at(row, p.steps)
                if (fromRow != null) return fromRow
            }
            return ApiJson.at(root, p.steps)
        }

        private fun binary(b: Binary, depth: Int): Any? {
            val l = eval(b.left, depth + 1)
            if (b.op == "??") return if (isEmpty(l)) eval(b.right, depth + 1) else l
            val r = eval(b.right, depth + 1)
            return when (b.op) {
                "+" -> {
                    val ln = strictNum(l)
                    val rn = strictNum(r)
                    if (ln != null && rn != null) ln + rn else text(l, context) + text(r, context)
                }
                "-", "*", "/", "%" -> {
                    val ln = num(l) ?: throw FormulaException("${b.op} needs numbers")
                    val rn = num(r) ?: throw FormulaException("${b.op} needs numbers")
                    when (b.op) {
                        "-" -> ln - rn
                        "*" -> ln * rn
                        "/" -> if (rn == 0.0) throw FormulaException("can't divide by zero") else ln / rn
                        else -> if (rn == 0.0) throw FormulaException("can't divide by zero") else ln % rn
                    }
                }
                "==" -> same(l, r)
                "!=" -> !same(l, r)
                else -> {
                    val ln = num(l)
                    val rn = num(r)
                    val cmp = if (ln != null && rn != null) ln.compareTo(rn) else text(l, context).compareTo(text(r, context))
                    when (b.op) {
                        "<" -> cmp < 0
                        "<=" -> cmp <= 0
                        ">" -> cmp > 0
                        else -> cmp >= 0
                    }
                }
            }
        }

        private fun same(l: Any?, r: Any?): Boolean {
            val ln = strictNum(l)
            val rn = strictNum(r)
            if (ln != null && rn != null) return ln == rn
            if (l == null || l == JSONObject.NULL) return r == null || r == JSONObject.NULL
            return text(l, context) == text(r, context)
        }

        private fun call(name: String, args: List<Expr>, depth: Int): Any? {
            // if() works out only the side it picks.
            if (name == "if") {
                if (args.size !in 2..3) throw FormulaException("if needs a test, a value, and optionally another")
                return if (truthy(eval(args[0], depth + 1))) eval(args[1], depth + 1) else args.getOrNull(2)?.let { eval(it, depth + 1) } ?: ""
            }
            val v = args.map { eval(it, depth + 1) }
            fun arg(i: Int): Any? = v.getOrNull(i)
            fun n(i: Int, default: Double? = null): Double =
                num(arg(i)) ?: default ?: throw FormulaException("$name needs a number" + if (i > 0) " as value ${i + 1}" else "")
            fun s(i: Int): String = text(arg(i), context)
            fun list(i: Int): List<Any?> = asList(arg(i)) ?: throw FormulaException("$name needs a list")
            fun zone(i: Int): ZoneId = if (arg(i) == null) context.zone else try {
                ZoneId.of(s(i))
            } catch (e: java.time.DateTimeException) {
                throw FormulaException("'${s(i)}' isn't a time zone, like 'Europe/London'")
            }
            fun need(range: IntRange) {
                if (v.size !in range) throw FormulaException("$name takes ${if (range.first == range.last) "${range.first}" else "${range.first} to ${range.last}"} values")
            }
            return when (name) {
                "upper" -> { need(1..1); s(0).uppercase(context.locale) }
                "lower" -> { need(1..1); s(0).lowercase(context.locale) }
                "title" -> { need(1..1); s(0).lowercase(context.locale).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(context.locale) } } }
                "trim" -> { need(1..1); s(0).trim() }
                "len" -> { need(1..1); asList(arg(0))?.size?.toDouble() ?: s(0).length.toDouble() }
                "left" -> { need(2..2); s(0).take(n(1).toInt().coerceAtLeast(0)) }
                "right" -> { need(2..2); s(0).takeLast(n(1).toInt().coerceAtLeast(0)) }
                "replace" -> { need(3..3); if (s(1).isEmpty()) s(0) else s(0).replace(s(1), s(2)) }
                "round" -> { need(1..2); round(n(0), n(1, 0.0).toInt()) }
                "floor" -> { need(1..1); Math.floor(n(0)) }
                "ceil" -> { need(1..1); Math.ceil(n(0)) }
                "abs" -> { need(1..1); Math.abs(n(0)) }
                "min" -> { need(1..8); numbers(v).minOrNull() ?: throw FormulaException("min needs numbers") }
                "max" -> { need(1..8); numbers(v).maxOrNull() ?: throw FormulaException("max needs numbers") }
                "fixed" -> { need(2..2); BigDecimal(n(0)).setScale(n(1).toInt().coerceIn(0, 10), RoundingMode.HALF_UP).toPlainString() }
                "thousands" -> { need(1..1); NumberFormat.getNumberInstance(context.locale).apply { maximumFractionDigits = 2 }.format(n(0)) }
                "compact" -> { need(1..1); compact(n(0)) }
                "percent" -> { need(1..2); show(round(n(0) * 100, n(1, 0.0).toInt()), context) + "%" }
                "convert" -> { need(3..3); Units.convert(n(0), s(1), s(2)) }
                "count" -> { need(1..1); list(0).size.toDouble() }
                "first" -> { need(1..1); list(0).firstOrNull() }
                "last" -> { need(1..1); list(0).lastOrNull() }
                "at" -> { need(2..2); list(0).getOrNull(n(1).toInt()) }
                "join" -> {
                    need(2..3)
                    val field = arg(2)?.let { s(2) }
                    list(0).take(MAX_LIST).joinToString(s(1)) { item -> text(if (field != null) ApiJson.at(item, ApiJson.steps(field)) else item, context) }
                }
                "sum", "avg" -> {
                    need(1..2)
                    val field = arg(1)?.let { s(1) }
                    val values = list(0).take(MAX_LIST).mapNotNull { item -> num(if (field != null) ApiJson.at(item, ApiJson.steps(field)) else item) }
                    if (name == "sum") values.sum() else if (values.isEmpty()) throw FormulaException("avg of nothing") else values.average()
                }
                "countdown" -> { need(1..1); countdown(time(arg(0))) }
                "until" -> { need(1..1); val t = time(arg(0)); changeAt(context.now - Math.floorMod(context.now - t, 1000L) + 1000); Math.floorDiv(t - context.now, 1000L).toDouble() }
                "ago" -> { need(1..1); ago(time(arg(0))) }
                "time" -> { need(1..2); clock(time(arg(0)), zone(1)) }
                "date" -> { need(1..3); date(time(arg(0)), arg(1)?.let { s(1) }, zone(2)) }
                "datetime" -> { need(1..1); time(arg(0)).toDouble() }
                "default" -> { need(2..2); if (isEmpty(arg(0))) arg(1) else arg(0) }
                "number" -> { need(1..1); num(arg(0)) }
                "text" -> { need(1..1); s(0) }
                else -> throw FormulaException("there's no function called $name")
            }.let { if (it is Double && (it.isNaN() || it.isInfinite())) throw FormulaException("that isn't a number") else it }
        }

        /** A time from the JSON as epoch millis: ISO 8601 text, or seconds or millis since 1970. */
        fun time(value: Any?): Long = Times.parse(value) ?: throw FormulaException("${text(value, context).take(40)} isn't a time")

        /** "2d 4h", "4h 12m", "12:04", "0:00" once it is past. Each step down is precise to its smallest unit. */
        private fun countdown(at: Long): String {
            val left = at - context.now
            if (left <= 0) return "0:00"
            val secs = (left + 999) / 1000
            val d = secs / 86400
            val h = secs % 86400 / 3600
            val m = secs % 3600 / 60
            val s = secs % 60
            // The text changes when the displayed unit ticks over: work out that moment.
            val unit = when {
                d > 0 -> 3600L
                h > 0 -> 60L
                else -> 1L
            }
            val nextSecs = (secs - 1) / unit * unit
            changeAt(at - nextSecs * 1000)
            return when {
                d > 0 -> "${d}d ${h}h"
                h > 0 -> "${h}h ${m}m"
                else -> "%d:%02d".format(m, s)
            }
        }

        private fun ago(at: Long): String {
            val gone = (context.now - at).coerceAtLeast(0)
            val mins = gone / 60_000
            val unit = when {
                mins < 60 -> 60_000L
                mins < 60 * 24 -> 3_600_000L
                else -> 86_400_000L
            }
            changeAt(at + (gone / unit + 1) * unit)
            return when {
                mins < 1 -> "now"
                mins < 60 -> "${mins}m"
                mins < 60 * 24 -> "${mins / 60}h"
                else -> "${mins / (60 * 24)}d"
            }
        }

        private fun clock(at: Long, zone: ZoneId): String =
            DateTimeFormatter.ofPattern(if (context.is24Hour) "HH:mm" else "h:mm a", context.locale).format(Instant.ofEpochMilli(at).atZone(zone))

        private fun date(at: Long, pattern: String?, zone: ZoneId): String {
            val format = try {
                DateTimeFormatter.ofPattern(pattern ?: "EEE d MMM", context.locale)
            } catch (e: IllegalArgumentException) {
                throw FormulaException("'$pattern' isn't a date pattern")
            }
            return try {
                format.format(Instant.ofEpochMilli(at).atZone(zone))
            } catch (e: java.time.DateTimeException) {
                throw FormulaException("'$pattern' isn't a date pattern")
            }
        }
    }

    // ---- values ----

    private fun isEmpty(v: Any?): Boolean = v == null || v == JSONObject.NULL || (v is String && v.isEmpty())

    private fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "false" && v != "0"
        is JSONArray -> v.length() > 0
        else -> true
    }

    /** A number, also from text that is one ("42", "3.5"). */
    internal fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    /** A number only when it is one, so + joins text that merely looks like a number to text. */
    private fun strictNum(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun numbers(values: List<Any?>): List<Double> = values.flatMap { asList(it) ?: listOf(it) }.mapNotNull(::num)

    private fun asList(v: Any?): List<Any?>? = when (v) {
        is JSONArray -> (0 until minOf(v.length(), MAX_LIST)).map { v.opt(it) }
        is List<*> -> v
        else -> null
    }

    private fun round(x: Double, digits: Int): Double =
        BigDecimal(x).setScale(digits.coerceIn(0, 10), RoundingMode.HALF_UP).toDouble()

    private fun compact(x: Double): String {
        val a = Math.abs(x)
        val (div, suffix) = when {
            a >= 1e12 -> 1e12 to "T"
            a >= 1e9 -> 1e9 to "B"
            a >= 1e6 -> 1e6 to "M"
            a >= 1e3 -> 1e3 to "k"
            else -> return plain(x)
        }
        return plain(round(x / div, if (a / div < 10) 1 else 0)) + suffix
    }

    /** A number as people write it: no ".0", no float noise ("0.30000000000000004" is 0.3). */
    private fun plain(x: Double): String {
        if (x == Math.floor(x) && Math.abs(x) < 1e15) return x.toLong().toString()
        return BigDecimal(x).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /** How a value shows in a template. */
    internal fun show(v: Any?, context: FormulaContext): String = text(v, context)

    private fun text(v: Any?, context: FormulaContext): String = when (v) {
        null, JSONObject.NULL -> ""
        is Double -> plain(v)
        is Float -> plain(v.toDouble())
        is Number -> v.toString()
        is Boolean -> if (v) "yes" else "no"
        is String -> v
        is JSONArray -> "${v.length()} items"
        is JSONObject -> "{…}"
        is List<*> -> "${v.size} items"
        else -> v.toString()
    }
}

/** Times as APIs send them. */
object Times {
    /** Before this, a number is seconds since 1970 (until the year 5138), else milliseconds. */
    private const val SECONDS_BELOW = 100_000_000_000L

    /**
     * [value] as epoch millis, or null: a number of seconds or millis since 1970, or ISO 8601
     * text with an offset ("2026-03-08T04:00:00Z", "+05:30"), a zone ("[Europe/London]"), or
     * neither ("2026-03-08T04:00:00" and "2026-03-08", read as UTC: an API that leaves the zone
     * out almost always means UTC). Text that is all digits counts as a number.
     */
    fun parse(value: Any?): Long? {
        when (value) {
            is Number -> return fromNumber(value.toDouble())
            !is String -> return null
        }
        val t = (value as String).trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return fromNumber(it) }
        val iso = t.replace(' ', 'T')
        return runCatching { ZonedDateTime.parse(iso, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            // "2026-03-08T04:00:00.000+0000" (no colon in the offset), as some servers send.
            ?: runCatching { OffsetDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX")).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun fromNumber(n: Double): Long? {
        if (n.isNaN() || n.isInfinite() || n < 0) return null
        return if (n < SECONDS_BELOW) (n * 1000).toLong() else n.toLong()
    }
}

/** The units [BuiltInFormulas]' convert() knows, by kind; a unit converts only within its kind. */
internal object Units {
    private class U(val kind: String, val factor: Double, val offset: Double = 0.0)

    private val units: Map<String, U> = buildMap {
        fun add(kind: String, factor: Double, vararg names: String, offset: Double = 0.0) = names.forEach { put(it, U(kind, factor, offset)) }
        add("length", 1.0, "m", "metre", "meter", "metres", "meters")
        add("length", 1000.0, "km", "kilometre", "kilometer", "kilometres", "kilometers")
        add("length", 0.01, "cm")
        add("length", 0.001, "mm")
        add("length", 1609.344, "mi", "mile", "miles")
        add("length", 0.3048, "ft", "foot", "feet")
        add("length", 0.0254, "in", "inch", "inches")
        add("length", 0.9144, "yd", "yard", "yards")
        add("mass", 1.0, "kg")
        add("mass", 0.001, "g")
        add("mass", 0.45359237, "lb", "lbs")
        add("mass", 0.028349523125, "oz")
        add("mass", 1000.0, "t", "tonne")
        add("speed", 1.0, "m/s", "ms")
        add("speed", 1 / 3.6, "km/h", "kmh", "kph")
        add("speed", 0.44704, "mph")
        add("speed", 0.514444, "kn", "knot", "knots")
        add("volume", 1.0, "l", "litre", "liter")
        add("volume", 0.001, "ml")
        add("volume", 3.785411784, "gal", "gallon")
        add("data", 1.0, "b", "byte", "bytes")
        add("data", 1e3, "kb")
        add("data", 1e6, "mb")
        add("data", 1e9, "gb")
        add("data", 1e12, "tb")
        add("data", 1024.0, "kib")
        add("data", 1024.0 * 1024, "mib")
        add("data", 1024.0 * 1024 * 1024, "gib")
        add("time", 1.0, "s", "sec", "second", "seconds")
        add("time", 0.001, "msec", "millis")
        add("time", 60.0, "min", "minute", "minutes")
        add("time", 3600.0, "h", "hr", "hour", "hours")
        add("time", 86400.0, "d", "day", "days")
        add("pressure", 100.0, "hpa", "mbar")
        add("pressure", 6894.757, "psi")
        add("pressure", 3386.389, "inhg")
        // Temperatures as kelvin = value * factor + offset.
        add("temperature", 1.0, "k", "kelvin")
        add("temperature", 1.0, "c", "°c", "celsius", offset = 273.15)
        add("temperature", 5.0 / 9.0, "f", "°f", "fahrenheit", offset = 273.15 - 32 * 5.0 / 9.0)
    }

    val names: Set<String> get() = units.keys

    fun convert(value: Double, from: String, to: String): Double {
        val a = units[from.trim().lowercase(Locale.ROOT)] ?: throw FormulaException("convert doesn't know '$from'")
        val b = units[to.trim().lowercase(Locale.ROOT)] ?: throw FormulaException("convert doesn't know '$to'")
        if (a.kind != b.kind) throw FormulaException("can't convert $from to $to")
        val base = value * a.factor + a.offset
        return (base - b.offset) / b.factor
    }
}
