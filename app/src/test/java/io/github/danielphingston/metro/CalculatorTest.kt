package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.search.Calculator
import io.github.danielphingston.metro.ui.search.UnitConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CalculatorTest {
    private val us = Locale.US
    private fun calc(s: String, locale: Locale = us) = Calculator.evaluate(s, locale)?.text

    @Test
    fun basicArithmeticAndPrecedence() {
        assertEquals("7", calc("1+2*3"))
        assertEquals("9", calc("(1+2)*3"))
        assertEquals("2.5", calc("5/2"))
        assertEquals("-1", calc("2-3"))
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("1", calc("10 - 3 - 6"))
        assertEquals("2", calc("16 / 4 / 2"))
    }

    @Test
    fun powerIsRightAssociativeAndBeatsUnaryMinus() {
        assertEquals("512", calc("2^3^2"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("1.41421356237", calc("2^0.5"))
        assertEquals("1024", calc("2**10"))
    }

    @Test
    fun percentIsPostfixOrModulo() {
        assertEquals("0.5", calc("50%"))
        assertEquals("360", calc("2400*15%"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("1", calc("10%3"))
        assertEquals("20.1", calc("20+10%"))
    }

    @Test
    fun percentOf() {
        assertEquals("360", calc("15% of 2400"))
        assertEquals("360", calc("15 % of 2400"))
        assertEquals("7.5", calc("7.5% of 100"))
        assertEquals("50", calc("10% of (200+300)"))
        assertNull(calc("15% of"))
        assertNull(calc("abc% of 5"))
    }

    @Test
    fun decimalsAreExact() {
        assertEquals("0.3", calc("0.1+0.2"))
        assertEquals("3.5", calc("1.5+2"))
        assertEquals("0.5", calc(".25*2"))
    }

    @Test
    fun commaDecimalsAndLocales() {
        assertEquals("3.5", calc("1,5+2"))
        assertEquals("3,5", calc("1,5+2", Locale.GERMANY))
        assertEquals("3,5", calc("1.5+2", Locale.GERMANY))
        assertEquals("2000", calc("1,000*2")) // a thousands group in an English locale
        assertEquals("2", calc("1,000*2", Locale.GERMANY)) // a decimal comma in a German one
        assertEquals("2000000", calc("1,000,000*2/1"))
        assertEquals("2469", calc("1.234,5*2"))
        assertEquals("3,14", calc("3,14*1", Locale.FRANCE))
    }

    @Test
    fun implicitMultiplicationAndSymbols() {
        assertEquals("14", calc("2(3+4)"))
        assertEquals("6", calc("2×3"))
        assertEquals("3", calc("6÷2"))
        assertEquals("1", calc("3−2"))
        assertEquals("4", calc("2+2="))
    }

    @Test
    fun largeAndSmallResults() {
        assertEquals("1E+20", calc("10^20"))
        assertEquals("0.000000001".let { "1E-9" }, calc("10^-9"))
        assertEquals("1000000000000", calc("10^12"))
        assertEquals("0.333333333333", calc("1/3"))
    }

    @Test
    fun garbageIsNoAnswer() {
        listOf(
            "", "  ", "abc", "5", "(5)", "-5", "1+", "+", "*2", "2**", "(1+2", "1+2)", "1/0", "5%0.0/0", "0^-1", "10 % 0",
            "1..2", "3.14.15", "192.168.1.1", "10:30", "1,", "2 3", "hello world", "2+a", "9^9^9", "1e5+1", "$5+2", "((((", "()",
            "1 2 3 4", "2+*3", "wifi", "5 km",
        ).forEach { assertNull("'$it' should give nothing", calc(it)) }
    }

    @Test
    fun neverCrashesOnOddInput() {
        val odd = listOf("9".repeat(500) + "+1", "(".repeat(300) + "1" + ")".repeat(300), "1+".repeat(200) + "1", "٣+٤", "\u0000+1", "2^99999", "-".repeat(100) + "1+1")
        odd.forEach { Calculator.evaluate(it, us) } // must simply not throw
    }

    @Test
    fun answerRemembersWhatWasTyped() {
        assertEquals(" 1 + 1 ".trim(), Calculator.evaluate(" 1 + 1 ", us)!!.input)
    }

    // --- units -----------------------------------------------------------------------------

    private fun unit(s: String, locale: Locale = us) = UnitConverter.convert(s, locale)?.text

    @Test
    fun convertsLengthMassAndTemperature() {
        assertEquals("3.10686 mi", unit("5 km in miles"))
        assertEquals("21.1111 °C", unit("70 f in c"))
        assertEquals("5.51156 lb", unit("2.5 kg to lb"))
        assertEquals("-40 °F", unit("-40 c to f"))
        assertEquals("273.15 K", unit("0 celsius in kelvin"))
        assertEquals("30.48 cm", unit("1 ft as cm"))
        assertEquals("2.54 cm", unit("1 in in cm"))
        assertEquals("1000 m", unit("1km in m"))
    }

    @Test
    fun convertsTheOtherKinds() {
        assertEquals("3.78541 L", unit("1 gal in l"))
        assertEquals("100 km/h", unit("100 kph to km/h"))
        assertEquals("62.1371 mph", unit("100 km/h in mph"))
        assertEquals("10.7639 ft²", unit("1 m2 in sq ft"))
        assertEquals("2.47105 acre", unit("1 ha in acres"))
        assertEquals("90 min", unit("1.5 hours to minutes"))
        assertEquals("1000 MB", unit("1 GB in MB"))
        assertEquals("1.07374 GB", unit("1 gib in gb"))
        assertEquals("8 bit", unit("1 byte in bits"))
    }

    @Test
    fun unitsUseLocaleDecimals() {
        assertEquals("5,51156 lb", unit("2,5 kg to lb", Locale.GERMANY))
        assertEquals("5.51156 lb", unit("2,5 kg to lb", us))
    }

    @Test
    fun badConversionsGiveNothing() {
        listOf(
            "5 km in kg", "5 foo in bar", "km in miles", "5 km in", "5 km", "in miles", "5 km in km2", "5 in", "abc in def",
            "5 c in", "1/0 km in m", "5 km miles", "hello to world",
        ).forEach { assertNull("'$it'", unit(it)) }
    }

    @Test
    fun inchesAndTheWordIn() {
        // "in" is both the unit and the keyword; the keyword is the one between the units.
        assertEquals("5 in", unit("5 in in in"))
    }
}
