package io.github.danielphingston.metro.ui.search

import java.math.BigDecimal
import java.util.Locale

/**
 * Offline unit conversion for "5 km in miles", "70 f in c", "2.5 kg to lb": length, mass,
 * temperature, volume, speed, area, time and data. Units are matched by their usual symbols and
 * names (case and accents ignored); a conversion between two different kinds of unit, or with an
 * unknown unit, is no answer. Currency is out of scope: it needs the network.
 */
object UnitConverter {
    private enum class Kind { LENGTH, MASS, TEMPERATURE, VOLUME, SPEED, AREA, TIME, DATA }

    /** [toBase] / [fromBase] turn the unit into and out of the kind's base unit (metre, kilogram, celsius, litre...). */
    private class Unit(
        val kind: Kind,
        val symbol: String,
        val toBase: (Double) -> Double,
        val fromBase: (Double) -> Double,
    )

    private val table = HashMap<String, Unit>()

    /** Symbols by their exact spelling, tried first: "Mb" (megabit) and "MB" (megabyte) differ only in case. */
    private val exact = HashMap<String, Unit>()

    private fun scaled(kind: Kind, symbol: String, factor: Double, vararg names: String) {
        val unit = Unit(kind, symbol, { it * factor }, { it / factor })
        (names.asList() + symbol).forEach { table.putIfAbsent(key(it), unit) }
        exact.putIfAbsent(symbol, unit)
    }

    private fun key(name: String) = name.trim().trimEnd('.').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    init {
        scaled(Kind.LENGTH, "mm", 0.001, "millimeter", "millimeters", "millimetre", "millimetres")
        scaled(Kind.LENGTH, "cm", 0.01, "centimeter", "centimeters", "centimetre", "centimetres")
        scaled(Kind.LENGTH, "m", 1.0, "meter", "meters", "metre", "metres")
        scaled(Kind.LENGTH, "km", 1000.0, "kilometer", "kilometers", "kilometre", "kilometres", "kms")
        scaled(Kind.LENGTH, "in", 0.0254, "inch", "inches", "\"")
        scaled(Kind.LENGTH, "ft", 0.3048, "foot", "feet", "'")
        scaled(Kind.LENGTH, "yd", 0.9144, "yard", "yards")
        scaled(Kind.LENGTH, "mi", 1609.344, "mile", "miles")
        scaled(Kind.LENGTH, "nmi", 1852.0, "nautical mile", "nautical miles")

        scaled(Kind.MASS, "mg", 0.000001, "milligram", "milligrams")
        scaled(Kind.MASS, "g", 0.001, "gram", "grams")
        scaled(Kind.MASS, "kg", 1.0, "kilogram", "kilograms", "kilo", "kilos", "kgs")
        scaled(Kind.MASS, "t", 1000.0, "tonne", "tonnes", "metric ton", "metric tons")
        scaled(Kind.MASS, "oz", 0.028349523125, "ounce", "ounces")
        scaled(Kind.MASS, "lb", 0.45359237, "lbs", "pound", "pounds")
        scaled(Kind.MASS, "st", 6.35029318, "stone", "stones")

        table[key("c")] = Unit(Kind.TEMPERATURE, "°C", { it }, { it })
        table[key("f")] = Unit(Kind.TEMPERATURE, "°F", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 })
        table[key("k")] = Unit(Kind.TEMPERATURE, "K", { it - 273.15 }, { it + 273.15 })
        listOf("°c", "celsius", "centigrade", "degc", "deg c", "degrees c", "degrees celsius").forEach { table[key(it)] = table.getValue(key("c")) }
        listOf("°f", "fahrenheit", "degf", "deg f", "degrees f", "degrees fahrenheit").forEach { table[key(it)] = table.getValue(key("f")) }
        listOf("kelvin", "kelvins").forEach { table[key(it)] = table.getValue(key("k")) }

        scaled(Kind.VOLUME, "ml", 0.001, "milliliter", "milliliters", "millilitre", "millilitres")
        scaled(Kind.VOLUME, "cl", 0.01, "centiliter", "centiliters", "centilitre", "centilitres")
        scaled(Kind.VOLUME, "dl", 0.1, "deciliter", "deciliters", "decilitre", "decilitres")
        scaled(Kind.VOLUME, "L", 1.0, "l", "liter", "liters", "litre", "litres")
        scaled(Kind.VOLUME, "m³", 1000.0, "m3", "cubic meter", "cubic meters", "cubic metre", "cubic metres")
        scaled(Kind.VOLUME, "tsp", 0.00492892159375, "teaspoon", "teaspoons")
        scaled(Kind.VOLUME, "tbsp", 0.01478676478125, "tablespoon", "tablespoons")
        scaled(Kind.VOLUME, "fl oz", 0.0295735295625, "floz", "fluid ounce", "fluid ounces")
        scaled(Kind.VOLUME, "cup", 0.2365882365, "cups")
        scaled(Kind.VOLUME, "pt", 0.473176473, "pint", "pints")
        scaled(Kind.VOLUME, "qt", 0.946352946, "quart", "quarts")
        scaled(Kind.VOLUME, "gal", 3.785411784, "gallon", "gallons")

        scaled(Kind.SPEED, "m/s", 1.0, "mps", "meters per second", "metres per second")
        scaled(Kind.SPEED, "km/h", 1000.0 / 3600, "kmh", "kph", "kmph", "km/hr", "kilometers per hour", "kilometres per hour")
        scaled(Kind.SPEED, "mph", 1609.344 / 3600, "mi/h", "miles per hour")
        scaled(Kind.SPEED, "kn", 1852.0 / 3600, "knot", "knots", "kt", "kts")
        scaled(Kind.SPEED, "ft/s", 0.3048, "fps", "feet per second")

        scaled(Kind.AREA, "mm²", 0.000001, "mm2", "sq mm", "square millimeter", "square millimeters")
        scaled(Kind.AREA, "cm²", 0.0001, "cm2", "sq cm", "square centimeter", "square centimeters")
        scaled(Kind.AREA, "m²", 1.0, "m2", "sqm", "sq m", "square meter", "square meters", "square metre", "square metres")
        scaled(Kind.AREA, "km²", 1_000_000.0, "km2", "sq km", "square kilometer", "square kilometers", "square kilometre", "square kilometres")
        scaled(Kind.AREA, "ha", 10_000.0, "hectare", "hectares")
        scaled(Kind.AREA, "acre", 4046.8564224, "acres", "ac")
        scaled(Kind.AREA, "in²", 0.00064516, "in2", "sqin", "sq in", "square inch", "square inches")
        scaled(Kind.AREA, "ft²", 0.09290304, "ft2", "sqft", "sq ft", "square foot", "square feet")
        scaled(Kind.AREA, "yd²", 0.83612736, "yd2", "sqyd", "sq yd", "square yard", "square yards")
        scaled(Kind.AREA, "mi²", 2_589_988.110336, "mi2", "sqmi", "sq mi", "square mile", "square miles")

        scaled(Kind.TIME, "ms", 0.001, "millisecond", "milliseconds", "msec", "msecs")
        scaled(Kind.TIME, "s", 1.0, "sec", "secs", "second", "seconds")
        scaled(Kind.TIME, "min", 60.0, "mins", "minute", "minutes")
        scaled(Kind.TIME, "h", 3600.0, "hr", "hrs", "hour", "hours")
        scaled(Kind.TIME, "d", 86_400.0, "day", "days")
        scaled(Kind.TIME, "wk", 604_800.0, "week", "weeks")
        scaled(Kind.TIME, "yr", 31_557_600.0, "year", "years", "yrs") // Julian year, 365.25 days

        scaled(Kind.DATA, "bit", 0.125, "bits")
        scaled(Kind.DATA, "B", 1.0, "byte", "bytes")
        scaled(Kind.DATA, "kbit", 125.0, "kilobit", "kilobits")
        scaled(Kind.DATA, "Mbit", 125_000.0, "megabit", "megabits")
        scaled(Kind.DATA, "Gbit", 125_000_000.0, "gigabit", "gigabits")
        // Bits are a lower-case b by convention; the byte symbols are listed below.
        exact["Kb"] = table.getValue(key("kbit"))
        exact["kb"] = table.getValue(key("kbit"))
        exact["Mb"] = table.getValue(key("Mbit"))
        exact["Gb"] = table.getValue(key("Gbit"))
        scaled(Kind.DATA, "KB", 1e3, "kilobyte", "kilobytes")
        scaled(Kind.DATA, "MB", 1e6, "megabyte", "megabytes")
        scaled(Kind.DATA, "GB", 1e9, "gigabyte", "gigabytes")
        scaled(Kind.DATA, "TB", 1e12, "terabyte", "terabytes")
        scaled(Kind.DATA, "PB", 1e15, "petabyte", "petabytes")
        scaled(Kind.DATA, "KiB", 1024.0, "kibibyte", "kibibytes")
        scaled(Kind.DATA, "MiB", 1024.0 * 1024, "mebibyte", "mebibytes")
        scaled(Kind.DATA, "GiB", 1024.0 * 1024 * 1024, "gibibyte", "gibibytes")
        scaled(Kind.DATA, "TiB", 1024.0 * 1024 * 1024 * 1024, "tebibyte", "tebibytes")
    }

    /** `<number> <unit> in|to|as|into|-> <unit>`; the first unit may hold spaces ("sq ft"). */
    private val pattern = Regex(
        """^\s*([-+]?[\d.,]+)\s*(.+?)(?:\s+(?:in|to|as|into)\s+|\s*(?:->|→|=)\s*)(.+?)\s*\??\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun lookup(name: String): Unit? = exact[name.trim().trimEnd('.')] ?: table[key(name)]

    /** The conversion typed in [input], or null when it isn't one or a unit is unknown or the kinds differ. */
    fun convert(input: String, locale: Locale = Locale.getDefault()): Answer? {
        // The pattern backtracks: a long paste is never a conversion and must not be matched.
        if (input.length > MAX_LENGTH) return null
        val match = pattern.matchEntire(input) ?: return null
        val amount = NumberText.parse(match.groupValues[1].removePrefix("+").removePrefix("-"), locale) ?: return null
        val negative = match.groupValues[1].startsWith("-")
        val from = lookup(match.groupValues[2]) ?: return null
        val to = lookup(match.groupValues[3]) ?: return null
        if (from.kind != to.kind) return null
        val value = (if (negative) amount.negate() else amount).toDouble()
        val result = to.fromBase(from.toBase(value))
        if (result.isNaN() || result.isInfinite()) return null
        return Answer("${NumberText.format(BigDecimal(result), SIGNIFICANT, locale)} ${to.symbol}", input.trim())
    }

    private const val SIGNIFICANT = 6
    private const val MAX_LENGTH = 120
}
