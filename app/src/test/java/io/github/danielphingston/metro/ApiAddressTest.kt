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
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.live.api.InMemoryApiSecrets
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.ApiDetail
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
