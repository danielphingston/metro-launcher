package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.api.ApiAddress
import io.github.danielphingston.metro.live.api.ApiHttp
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiRequest
import io.github.danielphingston.metro.live.api.ApiResponse
import io.github.danielphingston.metro.live.api.ApiSecrets
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.live.api.InMemoryApiSecrets
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.ApiDetail
import io.github.danielphingston.metro.ui.tiles.ApiImport
import io.github.danielphingston.metro.ui.tiles.withAddressField
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Collections
import java.util.Locale

/** The expanded view's address taken from the tile's answer, and followed when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ApiAddressTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = FormulaContext(1_795_762_800_000L, ZoneOffset.UTC, Locale.US, is24Hour = true)
    private val original = ApiStore.http

    @Before
    fun secrets() {
        ApiSecrets.override = InMemoryApiSecrets()
    }

    @After
    fun restore() {
        ApiStore.http = original
        ApiStore.clearMemory()
        ApiSecrets.override = null
    }

    private val answer = ApiJson.parse("""{"id": 25, "species": {"url": "https://pokeapi.co/api/v2/pokemon-species/25/"}, "bad": "javascript:alert(1)", "plain": "http://x.example.com/a"}""")

    @Test
    fun normalizeKeepsTemplatesWhoseFixedPartsMakeAnHttpsAddress() {
        assertEquals("https://pokeapi.co/api/v2/pokemon/{id}", ApiAddress.normalize("pokeapi.co/api/v2/pokemon/{id}"))
        assertEquals("https://pokeapi.co/api/v2/pokemon/{id}", ApiAddress.normalize("http://pokeapi.co/api/v2/pokemon/{id}"))
        assertEquals("{species.url}", ApiAddress.normalize("  {species.url} "))
        assertEquals("https://a.example.com/x", ApiAddress.normalize("a.example.com/x"))
        assertNull(ApiAddress.normalize("ftp://a.example.com/{id}"))
        assertNull(ApiAddress.normalize("https://a.example.com/{round(}"))
        assertNull(ApiAddress.normalize("https://a.example.com/" + "{id}".repeat(2_000)))
    }

    @Test
    fun resolveReadsTheAnswerAndNeverThrows() {
        assertEquals(ApiAddress.Resolved.Url("https://pokeapi.co/api/v2/pokemon-species/25/"), ApiAddress.resolve("{species.url}", answer, true, ctx))
        assertEquals(ApiAddress.Resolved.Url("https://pokeapi.co/api/v2/pokemon/25"), ApiAddress.resolve("https://pokeapi.co/api/v2/pokemon/{id}", answer, true, ctx))
        // An http link in the answer is fetched over https, like a typed one.
        assertEquals(ApiAddress.Resolved.Url("https://x.example.com/a"), ApiAddress.resolve("{plain}", answer, true, ctx))
        assertEquals(ApiAddress.Resolved.Waiting, ApiAddress.resolve("{species.url}", null, false, ctx))
        assertTrue(ApiAddress.resolve("{missing}", answer, true, ctx) is ApiAddress.Resolved.Problem)
        assertTrue(ApiAddress.resolve("{bad}", answer, true, ctx) is ApiAddress.Resolved.Problem)
        assertTrue(ApiAddress.resolve("https://a.com/{1/0}", answer, true, ctx) is ApiAddress.Resolved.Problem)
        assertTrue(ApiAddress.resolve("{species}", answer, true, ctx) is ApiAddress.Resolved.Problem)
        assertEquals(ApiAddress.Resolved.Url("https://a.example.com/x"), ApiAddress.resolve("https://a.example.com/x", null, false, ctx))
    }

    @Test
    fun theTilesHeadersGoOnlyToItsOwnSite() {
        val key = mapOf("X-Api-Key" to "secret")
        val tile = "https://api.example.com/list"
        assertEquals(key, ApiAddress.headersFor("https://api.example.com/item/{id}", "https://api.example.com/item/1", tile, key))
        assertEquals(emptyMap<String, String>(), ApiAddress.headersFor("{link}", "https://elsewhere.example.org/x", tile, key))
        // An address typed in full is the person's choice and keeps them.
        assertEquals(key, ApiAddress.headersFor("https://other.example.org/x", "https://other.example.org/x", tile, key))
    }

    @Test
    fun anAddressWithFieldsSurvivesSavingAndABadOneIsDropped() {
        val api = TileContent.Api("poke", ApiView("https://pokeapi.co/api/v2/pokemon/pikachu", mapOf(ApiSlot.TITLE to "{name}")), ApiView("{species.url}", mapOf(ApiSlot.TITLE to "{name}")))
        val json = TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, api)))
        assertEquals(api, TileJson.decode(JSONArray(json.toString())).single().content)
        val bad = json.getJSONObject(0)
        bad.getJSONObject("detail").put("url", "ftp://x/{id}")
        val read = TileJson.decode(JSONArray().put(bad)).singleOrNull()?.content as? TileContent.Api
        assertTrue(read == null || read.detail.url.isEmpty())
    }

    @Test
    fun randomAndDayOfYearWorkAndRandomStaysInItsRange() {
        // ctx.now is 2026-11-26 or so in UTC: dayOfYear matches java.time, and the text changes at the next midnight.
        val today = java.time.Instant.ofEpochMilli(ctx.now).atZone(ZoneOffset.UTC)
        val day = BuiltInFormulas.evaluate("{dayOfYear()}", null, ctx)
        assertEquals(today.dayOfYear.toString(), day.text)
        assertEquals(today.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), day.changesAt)
        assertEquals("1", BuiltInFormulas.evaluate("{dayOfYear('2026-01-01T12:00:00Z')}", null, ctx).text)
        assertEquals((today.dayOfYear * 7 % 1025 + 1).toString(), BuiltInFormulas.evaluate("{dayOfYear() * 7 % 1025 + 1}", null, ctx).text)
        val picks = (0 until 300).map { i -> BuiltInFormulas.evaluate("{random(1, 1025)}", null, ctx.copy(now = ctx.now + i)).text.toInt() }
        assertTrue(picks.all { it in 1..1025 })
        assertTrue("random picks differ between refreshes", picks.toSet().size > 200)
        // The same refresh (seed) gives the same pick, so a redraw doesn't change it; two calls draw separately.
        val seeded = ctx.copy(seed = 42L)
        val a = BuiltInFormulas.evaluate("{random(1, 1000000)} {random(1, 1000000)}", null, seeded).text
        assertEquals(a, BuiltInFormulas.evaluate("{random(1, 1000000)} {random(1, 1000000)}", null, seeded.copy(now = ctx.now + 5_000)).text)
        assertTrue(a, a.split(' ').let { it[0] != it[1] })
        assertEquals("7", BuiltInFormulas.evaluate("{random(7, 7)}", null, ctx).text)
        for (bad in listOf("{random(1)}", "{random(1.2, 1.8)}", "{random('a', 2)}", "{random(0, 1e15)}", "{dayOfYear(1, 2)}", "{dayOfYear('x')}")) {
            assertTrue(bad, BuiltInFormulas.evaluate(bad, null, ctx).error != null)
        }
    }

    @Test
    fun theTilesOwnAddressTakesFunctionsAfterAFixedSite() {
        assertEquals("https://pokeapi.co/api/v2/pokemon/{random(1, 1025)}", ApiAddress.tileAddress("pokeapi.co/api/v2/pokemon/{random(1, 1025)}"))
        assertEquals("https://pokeapi.co/api/v2/pokemon/{dayOfYear() * 7 % 1025 + 1}", ApiAddress.tileAddress("https://pokeapi.co/api/v2/pokemon/{dayOfYear() * 7 % 1025 + 1}"))
        assertEquals("https://a.example.com/x", ApiAddress.tileAddress("a.example.com/x"))
        // No answer yet: a field can't be read. The site can't come from a formula either.
        assertNull(ApiAddress.tileAddress("https://a.example.com/{id}"))
        assertNull(ApiAddress.tileAddress("{random(1, 2)}"))
        assertNull(ApiAddress.tileAddress("https://{random(1, 2)}.example.com/x"))
        assertNull(ApiAddress.tileAddress("https://a.example.com/{random(1,}"))
        assertTrue(ApiAddress.tileProblem("https://a.example.com/{id}")!!.contains("not fields"))
        assertTrue(ApiAddress.tileProblem("https://{random(1, 2)}.example.com/x")!!.contains("start with its site"))
        assertNull(ApiAddress.tileProblem("https://a.example.com/{random(1, 2)}"))
        val url = ApiAddress.fetchAddress("https://pokeapi.co/api/v2/pokemon/{random(1, 1025)}", ctx.now)!!
        assertTrue(url, Regex("https://pokeapi\\.co/api/v2/pokemon/\\d+").matches(url))
        val day = java.time.Instant.ofEpochMilli(ctx.now).atZone(java.time.ZoneId.systemDefault()).dayOfYear
        assertEquals("https://pokeapi.co/api/v2/pokemon/${day * 7 % 1025 + 1}", ApiAddress.fetchAddress("https://pokeapi.co/api/v2/pokemon/{dayOfYear() * 7 % 1025 + 1}", ctx.now))
        // Headers still go to the tile's site, whatever the formula picked.
        val key = mapOf("X-Api-Key" to "secret")
        assertEquals(key, ApiAddress.headersFor("https://a.example.com/{n}", "https://a.example.com/7", "https://a.example.com/{random(1, 9)}", key))
    }

    @Test
    fun eachRefreshFetchesAFreshRandomAddressUnderOneCachedCopy() = runBlocking {
        val fetched = Collections.synchronizedList(ArrayList<String>())
        val conditional = Collections.synchronizedList(ArrayList<Boolean>())
        ApiStore.http = ApiHttp { url, headers, _ ->
            fetched += url
            conditional += "If-None-Match" in headers
            ApiResponse("""{"id": ${url.substringAfterLast('/')}}""".toByteArray(), etag = "\"e\"")
        }
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val request = ApiRequest("https://pokeapi.co/api/v2/pokemon/{random(1, 1000000)}", emptyMap())
        repeat(3) {
            ApiStore.refresh(app, request, maxAgeMs = 0, force = true)
            Thread.sleep(3)
        }
        assertEquals(3, fetched.size)
        assertTrue(fetched.all { Regex("https://pokeapi\\.co/api/v2/pokemon/\\d+").matches(it) })
        assertTrue("a new number each refresh: $fetched", fetched.toSet().size > 1)
        // A copy from another address says nothing about this one: no If-None-Match.
        assertTrue(conditional.none { it })
        assertEquals(fetched.last().substringAfterLast('/'), (ApiStore.data.value[request.key]!!.root as org.json.JSONObject).get("id").toString())
    }

    @Test
    fun anAddressWithFunctionsSurvivesSavingAndTheImport() {
        val api = TileContent.Api("poke", ApiView("https://pokeapi.co/api/v2/pokemon/{random(1, 1025)}", mapOf(ApiSlot.TITLE to "{name}")), ApiView("", emptyMap()))
        val json = TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, api)))
        assertEquals(api, TileJson.decode(JSONArray(json.toString())).single().content)
        val read = ApiImport.parse("""{"url": "https://pokeapi.co/api/v2/pokemon/{dayOfYear() * 7 % 1025 + 1}", "tile": {"title": "{name}"}}""")
        assertEquals("https://pokeapi.co/api/v2/pokemon/{dayOfYear() * 7 % 1025 + 1}", (read as ApiImport.Parsed.Ok).draft.url)
        val withField = ApiImport.parse("""{"url": "https://pokeapi.co/api/v2/pokemon/{id}", "tile": {"title": "{name}"}}""") as ApiImport.Parsed.Ok
        assertEquals("", withField.draft.url)
        assertTrue(withField.notes.toString(), withField.notes.any { it.startsWith("url: can use functions") })
    }

    @Test
    fun pickingAFieldBuildsOrReplacesTheAddress() {
        assertEquals("https://pokeapi.co/api/v2/pokemon/{id}", withAddressField("https://pokeapi.co/api/v2/pokemon/", "id"))
        assertEquals("{species.url}", withAddressField("", "species.url"))
        assertEquals("{species.url}", withAddressField("{forms[0].url}", "species.url"))
    }

    @Test
    fun theExpandedViewFollowsTheLinkWhenTheTilesAnswerChanges() {
        val fetched = Collections.synchronizedList(ArrayList<String>())
        val species = java.util.concurrent.atomic.AtomicReference("https://pokeapi.co/api/v2/pokemon-species/25/")
        ApiStore.http = ApiHttp { url, _, _ ->
            fetched += url
            when {
                "species" in url -> ApiResponse("""{"name": "${url.trimEnd('/').substringAfterLast('/')}"}""".toByteArray())
                else -> ApiResponse("""{"species": {"url": "${species.get()}"}}""".toByteArray())
            }
        }
        val tileUrl = "https://pokeapi.co/api/v2/pokemon/pikachu"
        val api = TileContent.Api("poke", ApiView(tileUrl, mapOf(ApiSlot.TITLE to "{species.url}")), ApiView("{species.url}", mapOf(ApiSlot.TITLE to "species {name}")))
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) { ApiDetail(api, onEdit = {}, onDismiss = {}) }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("species 25").fetchSemanticsNodes().isNotEmpty() }
        // The tile updates with a new link: the open view fetches it.
        species.set("https://pokeapi.co/api/v2/pokemon-species/133/")
        runBlocking { ApiStore.refresh(ApplicationProvider.getApplicationContext(), ApiRequest(tileUrl), maxAgeMs = 0, force = true) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("species 133").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fetched.toString(), "https://pokeapi.co/api/v2/pokemon-species/133/" in fetched)
    }

    @Test
    fun theExpandedViewSaysWhyWhenTheAnswerHasNoLink() {
        ApiStore.http = ApiHttp { _, _, _ -> ApiResponse("""{"species": {"name": "no link here"}}""".toByteArray()) }
        val api = TileContent.Api("poke", ApiView("https://pokeapi.co/api/v2/pokemon/pikachu", mapOf(ApiSlot.TITLE to "{species.name}")), ApiView("{species.url}", mapOf(ApiSlot.TITLE to "{name}")))
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) { ApiDetail(api, onEdit = {}, onDismiss = {}) }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("the tile's answer has no value for the expanded view's address", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
