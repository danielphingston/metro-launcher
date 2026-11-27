package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiTileJson
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.ui.tiles.AiDownload
import io.github.danielphingston.metro.ui.tiles.AiState
import io.github.danielphingston.metro.ui.tiles.ApiImport
import io.github.danielphingston.metro.ui.tiles.ApiTarget
import io.github.danielphingston.metro.ui.tiles.TileAi
import io.github.danielphingston.metro.ui.tiles.askTileAi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** Reading a pasted, shared or AI-written tile setup, and holding it against the real answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ApiImportTest {
    private val pokemon = ApiJson.parse(javaClass.getResource("/api/pokemon-shaped.json")!!.readText())
    private val ctx = FormulaContext(1_795_762_800_000L, ZoneOffset.UTC, Locale.US, is24Hour = true)

    private fun ok(text: String): ApiImport.Parsed.Ok {
        val parsed = ApiImport.parse(text)
        assertTrue(parsed.toString(), parsed is ApiImport.Parsed.Ok)
        return parsed as ApiImport.Parsed.Ok
    }

    private fun failed(text: String): String {
        val parsed = ApiImport.parse(text)
        assertTrue(parsed.toString(), parsed is ApiImport.Parsed.Failed)
        return (parsed as ApiImport.Parsed.Failed).message
    }

    @Test
    fun aChatbotsAnswerIsReadThroughItsFencesAndChatter() {
        val r = ok(
            """
            Sure! Here's your tile:
            ```json
            {"name": "Pikachu", "url": "pokeapi.co/api/v2/pokemon/pikachu",
             "tile": {"title": "{name}", "value": "{weight}", "list": "stats", "rowText": "{stat.name}", "rowDetail": "{base_stat}"},
             "refreshMinutes": 30}
            ```
            Let me know if you want changes.
            """.trimIndent(),
        )
        assertEquals("Pikachu", r.draft.name)
        assertEquals("https://pokeapi.co/api/v2/pokemon/pikachu", r.draft.url)
        assertEquals("{name}", r.draft.tile[ApiTarget.TITLE])
        assertEquals("stats", r.draft.tile[ApiTarget.LIST])
        assertEquals("{base_stat}", r.draft.tile[ApiTarget.ROW_SECONDARY])
        assertEquals(30, r.draft.refreshMinutes)
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun whatIsntATileSetupSaysWhy() {
        assertTrue(failed("").startsWith("no tile setup found"))
        assertTrue(failed("just words").startsWith("no tile setup found"))
        assertTrue(failed("{ \"name\": ").startsWith("no tile setup found"))
        assertTrue(failed("{ name: [ }").startsWith("that isn't valid JSON"))
        assertTrue(failed("""{"name": "x"}""").startsWith("nothing usable in it"))
        assertTrue(failed("{" + "x".repeat(ApiImport.MAX_CHARS) + "}").contains("too long"))
        // Odd shapes never throw.
        listOf("""{"tile": 5}""", """{"tile": {"title": null}}""", """{"tile": {"title": ["a"]}, "headers": 7}""", "{}{}", "}{").forEach { ApiImport.parse(it) }
    }

    @Test
    fun headerValuesAreNeverImported() {
        val asObject = ok("""{"headers": {"X-Api-Key": "sk-secret"}, "tile": {"title": "{name}"}}""")
        assertEquals(listOf("X-Api-Key"), asObject.draft.headers)
        assertTrue(asObject.notes.any { "values aren't imported" in it })
        val asList = ok("""{"headers": [{"name": "Authorization2", "value": "Bearer x"}, "X-Key", "bad name!"], "tile": {"title": "{name}"}}""")
        assertEquals(listOf("Authorization2", "X-Key"), asList.draft.headers)
        assertTrue(asList.notes.any { "bad name!" in it })
        assertFalse(asList.draft.toString().contains("Bearer"))
        assertFalse(asObject.draft.toString().contains("sk-secret"))
    }

    @Test
    fun unknownKeysBadFormulasAndBadAddressesAreLeftOutWithANote() {
        val r = ok("""{"url": "ftp://x.example.com", "tile": {"title": "{name}", "colour": "red", "value": "{round(}", "line1": "${"x".repeat(600)}"}, "expanded": {"url": "ftp://{id}", "title": "{name}"}}""")
        assertEquals("", r.draft.url)
        assertEquals(mapOf(ApiTarget.TITLE to "{name}"), r.draft.tile)
        assertEquals("", r.draft.detailUrl)
        assertEquals(mapOf(ApiTarget.TITLE to "{name}"), r.draft.detail)
        assertTrue(r.notes.toString(), r.notes.size >= 5)
    }

    @Test
    fun theFormatIsStrictAndEveryErrorIsListedWhereItIs() {
        val r = ok(
            """{"name": 5, "url": 7, "colour": "red", "headers": "X-Key", "refreshMinutes": "15", "liveSeconds": 2.5,
               "tile": {"title": "{name}", "value": 60, "line1": ["a"], "url": "https://x.example.com", "list": "{stats}", "rowText": "{x}", "size": "wide"},
               "expanded": {"url": 3, "title": "{name}", "list": {"path": "stats", "text": "{stat.name}", "colour": "red", "detail": 5}},
               "detail": {"title": "{x}"}}""",
        )
        val errors = r.notes
        listOf(
            "\"colour\" isn't part of a tile setup: skipped",
            "name: must be text: skipped",
            "url: must be text: skipped, the tile keeps its own",
            "headers: must be a list of header names: skipped",
            "refreshMinutes: must be a whole number: skipped",
            "liveSeconds: must be a whole number: skipped",
            "tile.value: must be text: skipped",
            "tile.line1: must be text: skipped",
            "tile.url: the address goes at the top, as \"url\": skipped",
            "tile.list: must be a plain path like stats, without braces: skipped",
            "tile.rowText: rows need a \"list\": skipped",
            "tile.size: isn't a field a tile has: skipped",
            "expanded.url: must be text: skipped",
            "expanded.list.colour: isn't part of a list (path, text, detail, image): skipped",
            "expanded.list.detail: must be text: skipped",
            "\"detail\" and \"expanded\" are the same view: \"detail\" skipped",
        ).forEach { assertTrue("missing: $it in $errors", it in errors) }
        assertEquals(mapOf(ApiTarget.TITLE to "{name}"), r.draft.tile)
        assertEquals(mapOf(ApiTarget.TITLE to "{name}", ApiTarget.LIST to "stats", ApiTarget.ROW_PRIMARY to "{stat.name}"), r.draft.detail)
        assertEquals("", r.draft.name)
        assertNull(r.draft.refreshMinutes)
        assertNull(r.draft.liveSeconds)
    }

    @Test
    fun numbersOutOfRangeAreSkippedNotBent() {
        val r = ok("""{"tile": {"title": "{name}"}, "refreshMinutes": 1, "liveSeconds": 900, "name": "${"n".repeat(60)}"}""")
        assertNull(r.draft.refreshMinutes)
        assertNull(r.draft.liveSeconds)
        assertEquals("", r.draft.name)
        assertEquals(3, r.notes.size)
        assertEquals(0, ok("""{"tile": {"title": "{name}"}, "liveSeconds": 0}""").draft.liveSeconds)
    }

    @Test
    fun aSetupWithNothingUsableListsEveryError() {
        val p = ApiImport.parse("""{"tile": {"title": 1, "value": "{round(}"}, "extra": true}""")
        assertTrue(p is ApiImport.Parsed.Failed)
        assertEquals(3, (p as ApiImport.Parsed.Failed).errors.size)
    }

    @Test
    fun theExpandedViewCanFetchFromALinkInTheTilesAnswer() {
        val r = ok("""{"url": "https://pokeapi.co/api/v2/pokemon/pikachu", "tile": {"title": "{name}"}, "expanded": {"url": "{species.url}", "title": "{name}"}}""")
        assertEquals("{species.url}", r.draft.detailUrl)
        assertTrue(ApiImport.FORMAT.contains("its \"url\" can take a link from the tile's answer"))
    }

    @Test
    fun thePromptListsEverySupportedFunctionWithAWorkingExample() {
        BuiltInFormulas.FUNCTIONS.forEach { (name, example) ->
            assertTrue("$name missing", ApiImport.FORMAT.contains("  $example: "))
            assertNull("$example", BuiltInFormulas.problem("{$example}"))
        }
        assertFalse(ApiImport.FORMAT.contains("FUNCTION_LIST"))
    }

    @Test
    fun theListMayComeAsAnObject() {
        val r = ok("""{"tile": {"list": {"path": "stats", "text": "{stat.name}", "detail": "{base_stat}"}}}""")
        assertEquals("stats", r.draft.tile[ApiTarget.LIST])
        assertEquals("{stat.name}", r.draft.tile[ApiTarget.ROW_PRIMARY])
        assertEquals("{base_stat}", r.draft.tile[ApiTarget.ROW_SECONDARY])
    }

    @Test
    fun aTileCopiedOutOfALayoutIsReadAsIs() {
        val api = TileContent.Api(
            "poke",
            ApiView("https://pokeapi.co/api/v2/pokemon/pikachu", mapOf(ApiSlot.TITLE to "{name}"), ApiList("stats", "{stat.name}", "{base_stat}")),
            ApiView("{species.url}", mapOf(ApiSlot.TITLE to "{name}")),
            headers = listOf("X-Key"),
        )
        val json = ApiTileJson.encode(api, JSONObject()).toString()
        val r = ok(json)
        assertEquals("poke", r.draft.name)
        assertEquals("https://pokeapi.co/api/v2/pokemon/pikachu", r.draft.url)
        assertEquals("{species.url}", r.draft.detailUrl)
        assertEquals("stats", r.draft.tile[ApiTarget.LIST])
        assertEquals(listOf("X-Key"), r.draft.headers)
    }

    @Test
    fun exportReadsBackTheSameAndHoldsNoSecrets() {
        val draft = ApiImport.Draft(
            "poke", "https://pokeapi.co/api/v2/pokemon/pikachu", listOf("X-Key"),
            mapOf(ApiTarget.TITLE to "{name}", ApiTarget.VALUE to "{weight}"), "{species.url}", mapOf(ApiTarget.TITLE to "{name}"), 15,
        )
        assertEquals(draft, ok(ApiImport.export(draft)).draft)
    }

    @Test
    fun missingPathsNamesWhatTheAnswerDoesntHave() {
        assertEquals(emptyList<String>(), BuiltInFormulas.missingPaths("{name} {weight}", pokemon, ctx))
        assertEquals(listOf("zebra", "species.colour"), BuiltInFormulas.missingPaths("{zebra} {species.colour} {name}", pokemon, ctx))
        // A fallback says the field may be missing.
        assertEquals(emptyList<String>(), BuiltInFormulas.missingPaths("{zebra ?? 'none'}", pokemon, ctx))
        assertEquals(listOf("@.nope"), BuiltInFormulas.missingPaths("{@.nope}", pokemon, ctx, JSONObject("""{"a": 1}""")))
        assertNull(BuiltInFormulas.missingPaths("{round(}", pokemon, ctx))
    }

    @Test
    fun checkingKeepsOnlyWhatTheAnswerHas() {
        val draft = ApiImport.Draft(
            tile = mapOf(
                ApiTarget.TITLE to "{name}",
                ApiTarget.VALUE to "{weight_kg}",
                ApiTarget.LINE1 to "{nickname ?? 'no nickname'}",
                ApiTarget.LINE2 to "{1 / 0}",
                ApiTarget.LIST to "stats",
                ApiTarget.ROW_PRIMARY to "{stat.name}",
                ApiTarget.ROW_SECONDARY to "{stat.colour}",
            ),
        )
        val c = ApiImport.check(draft, pokemon, null, ctx)
        assertEquals("{name}", c.draft.tile[ApiTarget.TITLE])
        assertEquals("{nickname ?? 'no nickname'}", c.draft.tile[ApiTarget.LINE1])
        assertEquals("stats", c.draft.tile[ApiTarget.LIST])
        assertEquals("{stat.name}", c.draft.tile[ApiTarget.ROW_PRIMARY])
        assertFalse(ApiTarget.VALUE in c.draft.tile)
        assertFalse(ApiTarget.ROW_SECONDARY in c.draft.tile)
        assertTrue(c.notes.toString(), c.notes.any { "weight_kg" in it })
        assertTrue(c.notes.toString(), c.notes.any { "@.stat.colour" in it || "stat.colour" in it })
    }

    @Test
    fun aListThatIsntOneTakesItsRowsWithIt() {
        val draft = ApiImport.Draft(tile = mapOf(ApiTarget.TITLE to "{name}", ApiTarget.LIST to "species", ApiTarget.ROW_PRIMARY to "{name}"))
        val c = ApiImport.check(draft, pokemon, null, ctx)
        assertEquals(mapOf(ApiTarget.TITLE to "{name}"), c.draft.tile)
        // Rows with no list at all are dropped too.
        val noList = ApiImport.check(ApiImport.Draft(tile = mapOf(ApiTarget.TITLE to "{name}", ApiTarget.ROW_PRIMARY to "{x}")), pokemon, null, ctx)
        assertEquals(mapOf(ApiTarget.TITLE to "{name}"), noList.draft.tile)
        // A list with no row text gets one from its items.
        val auto = ApiImport.check(ApiImport.Draft(tile = mapOf(ApiTarget.LIST to "stats")), pokemon, null, ctx)
        assertEquals("{stat.name}", auto.draft.tile[ApiTarget.ROW_PRIMARY])
    }

    @Test
    fun anExpandedAddressFromAMissingFieldFallsBackToTheTiles() {
        val draft = ApiImport.Draft(tile = mapOf(ApiTarget.TITLE to "{name}"), detailUrl = "{species.link}", detail = mapOf(ApiTarget.TITLE to "{name}"))
        val c = ApiImport.check(draft, pokemon, null, ctx)
        assertEquals("", c.draft.detailUrl)
        assertEquals(mapOf(ApiTarget.TITLE to "{name}"), c.draft.detail)
        // A working one is kept; its fields wait for its own answer.
        val good = ApiImport.check(draft.copy(detailUrl = "{species.url}", detail = mapOf(ApiTarget.TITLE to "{genus}")), pokemon, null, ctx)
        assertEquals("{species.url}", good.draft.detailUrl)
        assertEquals("{genus}", good.draft.detail[ApiTarget.TITLE])
        assertTrue(good.notes.any { "weren't checked" in it })
    }

    @Test
    fun thePromptCarriesTheFormatTheAddressTheFieldsAndTheAsk() {
        val p = ApiImport.prompt("https://pokeapi.co/api/v2/pokemon/pikachu", "name and weight", pokemon)
        assertTrue(p.contains(ApiImport.FORMAT))
        assertTrue(p.contains("Reply with only the JSON object"))
        assertTrue(p.contains("The API: https://pokeapi.co/api/v2/pokemon/pikachu"))
        assertTrue(p.contains("weight = 60"))
        assertTrue(p.endsWith("What I want the tile to show: name and weight"))
        val blank = ApiImport.prompt("", "", null)
        assertTrue(blank.contains("<paste the API's https address here>"))
        assertTrue(blank.contains("<describe the tile here>"))
        assertTrue(blank.contains("ask me to paste a sample"))
    }

    @Test
    fun theFormatsOwnExampleReadsAsATile() {
        val example = ApiImport.FORMAT.substringAfter("one JSON object:\n").substringBefore("\nRules:")
        val r = ok(example)
        assertEquals("{name}", r.draft.tile[ApiTarget.TITLE])
        assertEquals(listOf("X-Api-Key"), r.draft.headers)
    }

    /** A stand-in model that counts tokens as four characters each. */
    private class CountingAi(private val limit: Int, private val answer: String) : TileAi {
        var asked = ""
        override suspend fun state() = AiState.Ready
        override fun download(): Flow<AiDownload> = emptyFlow()
        override suspend fun generate(system: String, prompt: String): String = answer.also { asked = prompt }
        override suspend fun tokenLimit() = limit
        override suspend fun countTokens(system: String, prompt: String) = (system.length + prompt.length) / 4
        override fun close() = Unit
    }

    @Test
    fun theAiIsToldAsManyFieldsAsFitAndThoseThePromptNamesFirst() = runBlocking {
        val ai = CountingAi(limit = 1_900, answer = """{"tile": {"title": "{name}", "value": "{weight}"}}""")
        val r = askTileAi(ai, pokemon, "https://pokeapi.co/api/v2/pokemon/pikachu", "base_experience")
        assertTrue(r is ApiImport.Parsed.Ok)
        val fields = ai.asked.lines().filter { " = " in it && !it.startsWith("Its answer") }
        assertTrue(fields.size.toString(), fields.size in 10 until 80)
        assertTrue(fields.take(3).toString(), fields.first().startsWith("base_experience"))
        assertTrue((ApiImport.FORMAT.length + ai.asked.length) / 4 <= 1_900 - 600 || fields.size <= 10)
    }
}
