package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.search.DateAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DateAnswersTest {
    // A Friday.
    private val today = LocalDate.of(2026, 10, 30)
    private fun ask(s: String, locale: Locale = Locale.US) = DateAnswers.answer(s, today, locale)?.text

    @Test
    fun daysUntilADate() {
        assertEquals("4 days · Tuesday, November 3, 2026", ask("how many days till nov 3rd?"))
        assertEquals("4 days · Tuesday, November 3, 2026", ask("days until 3 November"))
        assertEquals("56 days (8 weeks) · Friday, December 25, 2026", ask("days to christmas"))
        assertEquals("Today · Friday, October 30, 2026", ask("days until oct 30"))
        assertEquals("1 day · Saturday, October 31, 2026", ask("days left till halloween"))
        assertEquals("1 week, 4 days · Tuesday, November 10, 2026", ask("weeks until nov 10"))
        assertEquals("2 months, 2 days · Friday, January 1, 2027", ask("months until new year"))
        assertEquals("7 days · Friday, November 6, 2026", ask("how long until next friday"))
    }

    @Test
    fun aDateThatHasPassedThisYearIsNextYears() {
        assertEquals("350 days (50 weeks) · Friday, October 15, 2027", ask("days till oct 15"))
    }

    @Test
    fun daysSince() {
        assertEquals("302 days (43 weeks, 1 day) · Thursday, January 1, 2026", ask("days since jan 1"))
        assertEquals("4 days ago · Monday, October 26, 2026", ask("days until oct 26 2026"))
    }

    @Test
    fun dateInSomeDays() {
        assertEquals("Monday, November 9, 2026", ask("in 10 days"))
        assertEquals("Monday, November 9, 2026", ask("what day is it in 10 days"))
        assertEquals("Monday, November 9, 2026", ask("what is the date after 10 days"))
        assertEquals("Monday, November 9, 2026", ask("date after 10 days"))
        assertEquals("Monday, November 9, 2026", ask("10 days from now"))
        assertEquals("Tuesday, October 20, 2026", ask("10 days ago"))
        assertEquals("Friday, November 13, 2026 · in 14 days", ask("2 weeks from today"))
        assertEquals("Friday, November 13, 2026 · in 14 days", ask("in two weeks"))
        assertEquals("Monday, November 30, 2026 · in 31 days", ask("a month later"))
        assertEquals("Wednesday, November 4, 2026 · in 5 days", ask("1 day after nov 3"))
    }

    @Test
    fun namedDays() {
        assertEquals("Saturday, October 31, 2026", ask("tomorrow"))
        assertEquals("Sunday, November 1, 2026", ask("day after tomorrow"))
        assertEquals("Friday, October 30, 2026", ask("what is the date today"))
        assertEquals("Friday, October 30, 2026", ask("what's today's date"))
        assertEquals("Friday, October 30, 2026", ask("what day is it"))
        assertEquals("Friday, November 6, 2026 · in 7 days", ask("next friday"))
        assertEquals("Monday, November 2, 2026 · in 3 days", ask("what date is monday"))
        assertEquals("Tuesday, November 3, 2026 · in 4 days", ask("what day is nov 3"))
        assertEquals("Tuesday, November 3, 2026 · in 4 days", ask("nov 3"))
        assertEquals("Friday, December 25, 2026 · in 56 days", ask("when is christmas"))
        assertEquals("Tuesday, February 29, 2028 · in 487 days", ask("feb 29"))
    }

    @Test
    fun theDateFollowsTheLanguage() {
        assertEquals("Montag, 9. November 2026", ask("in 10 days", Locale.GERMANY))
    }

    @Test
    fun otherSearchesAreNotDates() {
        assertNull(ask("10 days"))
        assertNull(ask("today"))
        assertNull(ask("friday"))
        assertNull(ask("christmas"))
        assertNull(ask("since jan 1"))
        assertNull(ask("days to weeks"))
        assertNull(ask("may"))
        assertNull(ask("feb 30"))
        assertNull(ask("monday morning"))
        assertNull(ask("date of birth"))
        assertNull(ask("what is love"))
    }
}
