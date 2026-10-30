package io.github.danielphingston.metro.ui.search

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormatSymbols
import java.util.Locale

/** What a calculation or conversion answered, and what to show under it. */
data class Answer(val text: String, val input: String)

/** Reading and writing the numbers people type, with the decimal comma of their language. */
internal object NumberText {
    /** Whole numbers up to this many digits are never rounded. */
    private const val EXACT_DIGITS = 16
    private val token = Regex("""\d+(?:[.,]\d+)*|[.,]\d+""")

    /**
     * [text] as a number, or null when it isn't one. Either mark may be the decimal one: with both
     * present the last is; a lone comma is a decimal comma unless it is followed by exactly three
     * digits after one to three (a thousands group, "1,000") and [locale] doesn't use a decimal comma;
     * a mark repeated ("1,000,000") is grouping.
     */
    fun parse(text: String, locale: Locale): BigDecimal? {
        if (!token.matches(text)) return null
        val dots = text.count { it == '.' }
        val commas = text.count { it == ',' }
        val normalized = when {
            dots > 0 && commas > 0 -> {
                val decimal = if (text.lastIndexOf('.') > text.lastIndexOf(',')) '.' else ','
                val other = if (decimal == '.') ',' else '.'
                // Only one decimal mark, and every group before it sits between the other marks.
                if (text.count { it == decimal } != 1 || text.lastIndexOf(decimal) < text.lastIndexOf(other)) return null
                text.replace(other.toString(), "").replace(decimal, '.')
            }
            dots > 1 -> groups(text, '.') ?: return null
            commas > 1 -> groups(text, ',') ?: return null
            commas == 1 -> {
                val (head, tail) = text.split(',')
                val groupLike = tail.length == 3 && head.length in 1..3 && !head.startsWith("0")
                if (groupLike && DecimalFormatSymbols.getInstance(locale).decimalSeparator != ',') head + tail else "$head.$tail"
            }
            else -> text
        }
        return normalized.toBigDecimalOrNull()
    }

    /**
     * [text] without its [mark] when it is grouped in thousands ("1,000,000") or the Indian way, in
     * lakhs and crores ("1,00,00,000": twos, then the last three), else null.
     */
    private fun groups(text: String, mark: Char): String? {
        val parts = text.split(mark)
        val middle = parts.subList(1, parts.size - 1)
        val thousands = parts.first().length in 1..3 && middle.all { it.length == 3 }
        val indian = parts.first().length in 1..2 && middle.all { it.length == 2 }
        if (parts.last().length != 3 || !(thousands || indian)) return null
        return parts.joinToString("")
    }

    /**
     * [value] with at most [significant] digits, no trailing zeros, the decimal mark of [locale] and
     * no grouping, so the text can be copied as is. With [exactWholeNumbers] a whole number of up to 16 digits is
     * shown in full instead of rounded to [significant] digits. Very large and very small values use "E" notation.
     */
    fun format(value: BigDecimal, significant: Int, locale: Locale, exactWholeNumbers: Boolean = false): String {
        val exact = value.stripTrailingZeros()
        // A whole number that fits a double's exact range is shown in full: 2^50 is 1125899906842624, not 1.12589990684E+15.
        if (exactWholeNumbers && exact.signum() != 0 && exact.scale() <= 0 && exact.precision() - exact.scale() <= EXACT_DIGITS) return exact.toPlainString()
        val rounded = value.round(MathContext(significant)).stripTrailingZeros()
        if (rounded.signum() == 0) return "0"
        val magnitude = rounded.precision() - rounded.scale() // digits before the point
        val text = if (magnitude > 15 || magnitude < -6) rounded.toString() else rounded.toPlainString()
        return text.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }
}

/**
 * A small offline calculator: + - * (or x) / ^ and % with parentheses, "15% of 2400", decimals with a
 * point or a comma. [evaluate] answers only inputs that really are a calculation (at least one
 * operator; a lone percentage such as "50%" is not one), so a bare number, a word or a half-typed sum shows nothing. Division by zero and
 * results too large to show are no answer.
 */
object Calculator {
    private val percentOf = Regex("""^(.+?)\s*%\s*of\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val math = MathContext(20)
    private val times = Regex("""([\d)])\s*[xX]\s*(?=[\d(.,])""")

    fun evaluate(input: String, locale: Locale = Locale.getDefault()): Answer? {
        val shown = input.trim()
        if (shown.isEmpty() || shown.length > MAX_LENGTH) return null
        val text = shown.trimEnd('=', ' ')
            .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("**", "^")
            // "5x3" and "5 x 3": the x people type for times, only between two operands.
            .replace(times, "$1*")
        return try {
            val value = percentOf.matchEntire(text)?.let { match ->
                val rate = Parser(match.groupValues[1], locale).parseAll()
                val base = Parser(match.groupValues[2], locale).parseAll()
                rate.multiply(base, math).divide(HUNDRED, math)
            } ?: Parser(text, locale).parseAll(requireOperator = true)
            Answer(NumberText.format(value, SIGNIFICANT, locale, exactWholeNumbers = true), shown)
        } catch (_: Bad) {
            null
        } catch (_: ArithmeticException) {
            null // A remainder or power too large for BigDecimal.
        }
    }

    private const val MAX_LENGTH = 120
    private const val SIGNIFICANT = 12
    private val HUNDRED = BigDecimal(100)

    /** Not a calculation we can answer. */
    private class Bad : RuntimeException(null, null, false, false)

    private sealed interface Tok {
        data class Num(val value: BigDecimal) : Tok
        data class Op(val c: Char) : Tok
    }

    private fun tokenize(text: String, locale: Locale): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || ((c == '.' || c == ',') && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isDigit() || ((text[j] == '.' || text[j] == ',') && j + 1 < text.length && text[j + 1].isDigit()))) j++
                    out += Tok.Num(NumberText.parse(text.substring(i, j), locale) ?: throw Bad())
                    i = j
                }
                c in "+-*/^%()" -> { out += Tok.Op(c); i++ }
                else -> throw Bad()
            }
        }
        return out
    }

    private class Parser(text: String, locale: Locale) {
        private val tokens = tokenize(text, locale)
        private var pos = 0
        private var operations = 0

        fun parseAll(requireOperator: Boolean = false): BigDecimal {
            val value = expression()
            if (pos != tokens.size || (requireOperator && operations == 0)) throw Bad()
            return value
        }

        private fun peek(): Tok? = tokens.getOrNull(pos)
        private fun isOp(c: Char) = (peek() as? Tok.Op)?.c == c
        private fun startsOperand(index: Int) = when (val t = tokens.getOrNull(index)) {
            is Tok.Num -> true
            is Tok.Op -> t.c == '('
            null -> false
        }

        private fun expression(): BigDecimal {
            var left = term()
            while (isOp('+') || isOp('-')) {
                val plus = isOp('+')
                pos++
                val rightStart = pos
                val right = term()
                operations++
                // "100+10%" adds a tenth of the 100, not 0.1: a bare percentage after + or - is a share of the left side.
                val share = pos - rightStart == 2 && tokens[rightStart] is Tok.Num && (tokens[rightStart + 1] as? Tok.Op)?.c == '%'
                val delta = if (share) left.multiply(right, math) else right
                left = if (plus) left.add(delta, math) else left.subtract(delta, math)
            }
            return left
        }

        private fun term(): BigDecimal {
            var left = unary()
            while (true) {
                when {
                    isOp('*') -> { pos++; left = left.multiply(unary(), math) }
                    isOp('/') -> {
                        pos++
                        val right = unary()
                        if (right.signum() == 0) throw Bad()
                        left = left.divide(right, math)
                    }
                    // A percent sign followed by an operand is "modulo"; otherwise it was a postfix percent.
                    isOp('%') && startsOperand(pos + 1) -> {
                        pos++
                        val right = unary()
                        if (right.signum() == 0) throw Bad()
                        left = left.remainder(right, math)
                    }
                    // 2(3+4): a number straight before a bracket multiplies.
                    isOp('(') -> left = left.multiply(unary(), math)
                    else -> return left
                }
                operations++
            }
        }

        private fun unary(): BigDecimal = when {
            isOp('-') -> { pos++; unary().negate() }
            isOp('+') -> { pos++; unary() }
            else -> power()
        }

        private fun power(): BigDecimal {
            val base = postfix()
            if (!isOp('^')) return base
            pos++
            val exponent = unary()
            operations++
            return pow(base, exponent)
        }

        private fun postfix(): BigDecimal {
            var value = primary()
            while (isOp('%') && !startsOperand(pos + 1)) {
                pos++
                value = value.divide(HUNDRED, math)
            }
            return value
        }

        private fun primary(): BigDecimal {
            when (val t = peek()) {
                is Tok.Num -> { pos++; return t.value }
                is Tok.Op -> if (t.c == '(') {
                    pos++
                    val inner = expression()
                    if (!isOp(')')) throw Bad()
                    pos++
                    return inner
                }
                null -> Unit
            }
            throw Bad()
        }

        private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val whole = exponent.stripTrailingZeros().scale() <= 0
            if (whole && exponent.abs() <= MAX_POWER) {
                val n = exponent.toInt()
                if (n >= 0) return base.pow(n, math)
                if (base.signum() == 0) throw Bad()
                return BigDecimal.ONE.divide(base.pow(-n, math), math)
            }
            val result = Math.pow(base.toDouble(), exponent.toDouble())
            if (result.isNaN() || result.isInfinite()) throw Bad()
            return BigDecimal(result, math)
        }
    }

    private val MAX_POWER = BigDecimal(999)
}
