package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SearchKeywords
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.ui.search.Calculator
import io.github.danielphingston.metro.ui.search.DateAnswers
import io.github.danielphingston.metro.ui.search.LauncherActions
import io.github.danielphingston.metro.ui.search.QuickActionParser
import io.github.danielphingston.metro.ui.search.SearchHelp
import io.github.danielphingston.metro.ui.search.SearchRow
import io.github.danielphingston.metro.ui.search.SettingsShortcuts
import io.github.danielphingston.metro.ui.search.Suggestions
import io.github.danielphingston.metro.ui.search.UnitConverter
import io.github.danielphingston.metro.ui.search.searchRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchHelpTest {
    private fun phrases(feature: SmartFeature) =
        SearchHelp.groups.filter { it.feature == feature }.flatMap { it.examples }.map { it.phrase }

    /** The help page must not promise what search doesn't do: every phrase gets its feature's result. */
    @Test
    fun calculatorPhrasesAnswer() = phrases(SmartFeature.CALCULATOR).forEach {
        val answer = Calculator.evaluate(it, Locale.US) ?: UnitConverter.convert(it, Locale.US)
            ?: DateAnswers.answer(it, LocalDate.of(2026, 10, 30), Locale.US)
        assertNotNull(it, answer)
    }

    @Test
    fun quickActionPhrasesParse() = phrases(SmartFeature.QUICK_ACTIONS).forEach { assertNotNull(it, QuickActionParser.parse(it)) }

    @Test
    fun launcherActionPhrasesParse() = phrases(SmartFeature.LAUNCHER_ACTIONS).forEach {
        assertTrue(it, LauncherActions.parse(it).isNotEmpty())
    }

    @Test
    fun settingsPhrasesMatch() = phrases(SmartFeature.SETTINGS_SHORTCUTS).forEach {
        assertTrue(it, SettingsShortcuts.match(it) { true }.isNotEmpty())
    }

    @Test
    fun keywordPhrasesUseDefaultKeywords() = phrases(SmartFeature.SEARCH_KEYWORDS).forEach {
        assertNotNull(it, SearchKeywords.detect(it, SearchKeywords.defaults, active = true))
    }

    @Test
    fun examplesComeOnlyFromFeaturesThatAreOn() {
        val on = setOf(SmartFeature.QUICK_ACTIONS, SmartFeature.CONTACTS)
        val examples = SearchHelp.examples({ it in on }, day = 3)
        // Contacts' phrase is a name from the user's own phone, so it is never offered as an example.
        assertEquals(1, examples.size)
        assertTrue(examples.single().phrase in phrases(SmartFeature.QUICK_ACTIONS))
        assertTrue(SearchHelp.examples({ false }, day = 3).isEmpty())
    }

    @Test
    fun examplesAreDistinctAndStableForTheDay() {
        val today = SearchHelp.examples({ true }, day = 20_000)
        assertEquals(4, today.size)
        assertEquals(today.size, today.toSet().size)
        assertEquals(today, SearchHelp.examples({ true }, day = 20_000))
    }

    private fun rows(query: String, suggestions: Suggestions? = null) = searchRows(
        emptyList(), query, emptyList(), SearchEngine.GOOGLE, 0L,
        suggestions = suggestions, examples = SearchHelp.examples({ true }, day = 0),
    )

    @Test
    fun examplesLeadTheEmptySearchOnly() {
        val empty = rows("", Suggestions(emptyList(), listOf("wifi")))
        assertEquals(SearchRow.Caption("try typing"), empty[0])
        assertTrue(empty[1] is SearchRow.Examples)
        assertTrue(rows("").any { it is SearchRow.Examples })
        // The first character typed, even a space, takes them away.
        assertTrue(rows("t").none { it is SearchRow.Examples || it == SearchRow.Caption("try typing") })
        assertTrue(rows(" ").none { it is SearchRow.Examples })
    }
}
