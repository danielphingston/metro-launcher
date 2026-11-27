package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiRender
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.ui.tiles.ApiSuggest
import io.github.danielphingston.metro.ui.tiles.ApiTarget
import io.github.danielphingston.metro.ui.tiles.apiTileProblem
import io.github.danielphingston.metro.ui.tiles.viewOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale

/** Building a tile from an answer with no AI: a best guess, or the prompt's words matched to fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ApiSuggestTest {
    private val pokemon = ApiJson.parse(javaClass.getResource("/api/pokemon-shaped.json")!!.readText())
    private val ctx = FormulaContext(1_795_762_800_000L, ZoneOffset.UTC, Locale.US, is24Hour = true)

    /** Every build must save and render: each template names fields that exist. */
    private fun assertWorks(result: ApiSuggest.Result, root: Any?) {
        assertNull(apiTileProblem("https://example.com/a.json", "", result.templates, emptyMap(), emptyList()))
        val rendered = ApiRender.render(viewOf("https://example.com/a.json", result.templates), root, ctx, maxRows = 5)
        assertNull("render error: ${rendered.error}", rendered.error)
        assertFalse(rendered.blank)
    }

    @Test
    fun noPromptPicksANameANumberAPictureAndAList() {
        val r = ApiSuggest.suggest(pokemon)
        assertEquals("{name}", r.templates[ApiTarget.TITLE])
        assertTrue(r.templates[ApiTarget.IMAGE]!!.startsWith("{sprites."))
        assertTrue(ApiTarget.VALUE in r.templates)
        assertTrue(ApiTarget.LIST in r.templates)
        assertTrue(r.unmatched.isEmpty())
        assertWorks(r, pokemon)
    }

    @Test
    fun aPromptPutsTheFieldsItNames() {
        val r = ApiSuggest.suggest(pokemon, "pikachu with its weight and height and a picture")
        assertEquals("{name}", r.templates[ApiTarget.TITLE])
        assertEquals("{weight}", r.templates[ApiTarget.VALUE])
        assertEquals("height {height}", r.templates[ApiTarget.LINE1])
        assertTrue(r.templates[ApiTarget.IMAGE]!!.startsWith("{sprites."))
        assertTrue(r.unmatched.isEmpty())
        val rendered = ApiRender.render(viewOf("https://example.com/a.json", r.templates), pokemon, ctx, 0)
        assertEquals("pikachu", rendered.text(ApiSlot.TITLE))
        assertEquals("60", rendered.text(ApiSlot.VALUE))
        assertEquals("height 4", rendered.text(ApiSlot.LINE1))
    }

    @Test
    fun aWordNamingAListMakesItTheRowsAndTheWordsAfterNameTheirFields() {
        val r = ApiSuggest.suggest(pokemon, "name and stats with base_stat")
        assertEquals("stats", r.templates[ApiTarget.LIST])
        assertEquals("{base_stat}", r.templates[ApiTarget.ROW_SECONDARY])
        assertWorks(r, pokemon)
        val rows = ApiRender.render(viewOf("https://example.com/a.json", r.templates), pokemon, ctx, 6).rows
        assertEquals(6, rows.size)
    }

    @Test
    fun wordsThatMatchNothingAreReported() {
        val r = ApiSuggest.suggest(pokemon, "weight and zebra")
        assertEquals("{weight}", r.templates[ApiTarget.VALUE])
        assertEquals(listOf("zebra"), r.unmatched)
    }

    @Test
    fun timesBecomeCountdownsOrAgo() {
        val race = ApiJson.parse("""{"race":{"name":"Abu Dhabi","start":"2026-12-06T13:00:00Z"},"updated":"2026-11-27T10:00:00Z"}""")
        val r = ApiSuggest.suggest(race, "name countdown and updated ago")
        assertEquals("{race.name}", r.templates[ApiTarget.TITLE])
        assertEquals("{countdown(race.start)}", r.templates[ApiTarget.VALUE])
        assertEquals("{ago(updated)}", r.templates[ApiTarget.LINE1])
        assertWorks(r, race)
    }

    @Test
    fun aDocumentThatIsAListBuildsRows() {
        val list = ApiJson.parse("""[{"title":"one","score":3},{"title":"two","score":5}]""")
        val r = ApiSuggest.suggest(list)
        // A top-level list has no path to name, so the best guess makes no rows from it; it must not throw or make a broken tile.
        r.templates.forEach { (_, t) -> assertTrue(t.isNotBlank()) }
    }

    @Test
    fun nothingBuildsFromAnswersThatHoldNothing() {
        assertTrue(ApiSuggest.suggest(null).isEmpty)
        assertTrue(ApiSuggest.suggest(42).isEmpty)
        assertTrue(ApiSuggest.suggest(ApiJson.parse("{}")).isEmpty)
        assertTrue(ApiSuggest.suggest(ApiJson.parse("{}"), "anything at all").isEmpty)
    }

    @Test
    fun oddKeysAndDeepAnswersStillBuildWorkingTiles() {
        var deep = "1"
        repeat(40) { deep = "{\"a\":$deep}" }
        val odd = ApiJson.parse("""{"first name":"Ada","it's":"x","n":{"value":7},"deep":$deep}""")
        val r = ApiSuggest.suggest(odd, "first name and value")
        assertEquals("{['first name']}", r.templates[ApiTarget.TITLE])
        assertWorks(r, odd)
        assertWorks(ApiSuggest.suggest(odd), odd)
    }

    @Test
    fun promptWordsDropFillerAndRepeats() {
        assertEquals(listOf("weight", "height"), ApiSuggest.words("Show me the weight, and the HEIGHT and weight!"))
        assertTrue(ApiSuggest.words("   ").isEmpty())
    }
}
