package io.github.danielphingston.metro

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.api.ApiCheck
import io.github.danielphingston.metro.live.api.ApiHttp
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiProblem
import io.github.danielphingston.metro.live.api.ApiRender
import io.github.danielphingston.metro.live.api.ApiRequest
import io.github.danielphingston.metro.live.api.ApiResponse
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import io.github.danielphingston.metro.live.api.CachedApi
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.live.api.KeystoreApiSecrets
import io.github.danielphingston.metro.live.api.Times
import io.github.danielphingston.metro.live.feed.HttpStatusException
import io.github.danielphingston.metro.live.feed.TooLargeException
import io.github.danielphingston.metro.ui.tiles.rememberTileMetrics
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.ApiDetailBody
import io.github.danielphingston.metro.ui.tiles.ApiEditor
import io.github.danielphingston.metro.ui.tiles.ApiFace
import io.github.danielphingston.metro.ui.tiles.ApiTiles
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZoneOffset
import java.util.Locale

/**
 * Nothing about an API tile may crash the launcher: every bad answer, address, template, time,
 * picture or saved layout is fed in here, and each must end as a calm message or an empty slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ApiFailureTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val ctx = FormulaContext(1_795_762_800_000L, ZoneOffset.UTC, Locale.US, is24Hour = true)
    private val original = ApiStore.http

    @After
    fun restore() {
        ApiStore.http = original
        ApiStore.clearMemory()
    }

    /** Answers that aren't JSON, or are JSON too deep to read. */
    private val garbage = listOf(
        "", " ", "﻿", "<html><body>502 Bad Gateway</body></html>", "{", "[1, 2", "{\"a\": }", "nul", "tru",
        "{\"a\":1} trailing", "\"unterminated", "{\"a\":1}{\"b\":2}", "[".repeat(10_000), "{\"a\":".repeat(5_000),
        "NaN", "Infinity", "undefined", "{'single': 'quotes', bare: words, }",
    )

    /** Every way a template can be wrong, and some that are right but meet bad data. */
    private val templates = listOf(
        "{", "}", "{}", "{ }", "{{name}}", "{name", "name}", "{nope(}", "{nope(1)}", "{(((((}", "{1/0}", "{1 % 0}",
        "{-name}", "{name * 2}", "{sum(name)}", "{avg(list)}", "{min()}", "{max('a', 'b')}", "{round(1e308 * 10)}",
        "{countdown('garbage')}", "{countdown(1e300)}", "{countdown(-5)}", "{ago('2026-99-99')}", "{time(name, 'Mars/Phobos')}",
        "{date(1795762800, 'qqqqqqqqqq')}", "{date(1795762800, 'Q\\'')}", "{date(1e300)}", "{datetime('x')}", "{convert(1, 'kg', 'km')}",
        "{convert(1, 'parsec', 'm')}", "{join(name, 5)}", "{first(name).x.y.z}", "{last(42)[-99999999999]}", "{a.99999999999999999999}",
        "{a[99999999999999999999]}", "{a['unterminated]}", "{'x' + }", "{if()}", "{if(1, 2, 3, 4)}", "{default()}",
        "{" + "(".repeat(500) + "1" + ")".repeat(500) + "}", "{" + "first(".repeat(200) + "a" + ")".repeat(200) + "}",
        "{" + "a.".repeat(300) + "b}", "{" + "1+".repeat(5_000) + "1}", "x".repeat(20_000), "{\u0000}", "{name}\uD800",
    )

    /** Documents of every shape a template may meet. */
    private val roots: List<Any?> = listOf(
        null, JSONObject.NULL, "just text", 42, true, JSONArray(), JSONObject(),
        JSONArray((1..3_000).toList()), JSONObject().put("name", JSONObject.NULL).put("list", JSONArray()).put("a", JSONArray().put(1)),
        nested(60),
    )

    private fun nested(depth: Int): JSONObject {
        var obj = JSONObject().put("leaf", 1)
        repeat(depth) { obj = JSONObject().put("a", obj).put("list", JSONArray().put(obj)) }
        return obj
    }

    @Test
    fun answersThatArentJsonSaySoInsteadOfThrowing() = runBlocking {
        for (body in garbage) {
            ApiStore.http = ApiHttp { _, _, _ -> ApiResponse(body.toByteArray()) }
            val result = ApiStore.check(context, ApiRequest("https://api.example.com/x"))
            assertEquals(body.take(40), ApiCheck.Failed(ApiProblem.NOT_JSON), result)
        }
        // Bytes that aren't text at all.
        ApiStore.http = ApiHttp { _, _, _ -> ApiResponse(ByteArray(4096) { (it * 37).toByte() }) }
        assertEquals(ApiCheck.Failed(ApiProblem.NOT_JSON), ApiStore.check(context, ApiRequest("https://api.example.com/bin")))
    }

    @Test
    fun everyNetworkFailureBecomesAMessage() = runBlocking {
        val failures: List<Pair<() -> Nothing, ApiProblem>> = listOf(
            { throw SocketTimeoutException("slow") } to ApiProblem.OFFLINE,
            { throw UnknownHostException("nowhere") } to ApiProblem.OFFLINE,
            { throw javax.net.ssl.SSLHandshakeException("bad cert") } to ApiProblem.INSECURE,
            { throw TooLargeException() } to ApiProblem.TOO_LARGE,
            { throw HttpStatusException(500) } to ApiProblem.SERVER,
            { throw HttpStatusException(999) } to ApiProblem.NOT_FOUND,
            { throw IllegalStateException("odd") } to ApiProblem.OFFLINE,
            { throw SecurityException("no internet permission") } to ApiProblem.OFFLINE,
            { throw OutOfMemoryError("huge") } to ApiProblem.TOO_LARGE,
            { throw StackOverflowError() } to ApiProblem.NOT_JSON,
        )
        for ((i, failure) in failures.withIndex()) {
            val (thrown, problem) = failure
            ApiStore.http = ApiHttp { _, _, _ -> thrown() }
            val request = ApiRequest("https://api.example.com/f$i")
            assertEquals("check $i", problem, (ApiStore.check(context, request) as ApiCheck.Failed).problem)
            // The tile's own refresh keeps going and shows it.
            ApiStore.refresh(context, request, maxAgeMs = 0, force = true)
            val cached = ApiStore.data.value[request.key]!!
            assertEquals("refresh $i", problem, cached.error)
            assertTrue(ApiTiles.status(cached, ctx.now).isNotBlank())
        }
        // Addresses that can't be fetched never reach the network.
        ApiStore.http = ApiHttp { _, _, _ -> error("must not be called") }
        for (address in listOf("", " ", "ftp://x", "javascript:alert(1)", "https://", "file:///etc/hosts", "\u0000")) {
            assertEquals(address, ApiCheck.Failed(ApiProblem.BAD_ADDRESS), ApiStore.check(context, ApiRequest(address)))
        }
    }

    @Test
    fun aCorruptSavedCopyIsIgnored() = runBlocking {
        val request = ApiRequest("https://api.example.com/saved")
        ApiStore.http = ApiHttp { _, _, _ -> ApiResponse("{\"v\": 1}".toByteArray()) }
        ApiStore.refresh(context, request, maxAgeMs = 0, force = true)
        val dir = java.io.File(context.noBackupFilesDir, "api")
        for (junk in listOf("", "{", "{\"body\": 5}", "{\"body\": \"<html>\"}", "\u0000\u0001", "{\"fetchedAt\": \"soon\", \"body\": \"{}\"}")) {
            dir.listFiles()!!.forEach { it.writeText(junk) }
            ApiStore.clearMemory()
            ApiStore.load(context, request)
            assertNotNull(junk, ApiStore.data.value[request.key])
        }
    }

    @Test
    fun brokenTemplatesLeaveTheSlotEmptyAndSayWhy() {
        for (root in roots) {
            for (template in templates) {
                val e = BuiltInFormulas.evaluate(template, root, ctx)
                assertTrue(e.text.length <= 20_000)
                BuiltInFormulas.problem(template)
                for (row in listOf(root, null, JSONArray(), "x")) BuiltInFormulas.evaluate(template, root, ctx, row)
            }
            val view = ApiView(
                "https://api.example.com/x",
                ApiSlot.entries.associateWith { templates[it.ordinal % templates.size] },
                ApiList("list", "{", "{1/0}", "{'javascript:alert(1)'}"),
            )
            val rendered = ApiRender.render(view, root, ctx, maxRows = 12)
            assertTrue(rendered.error != null)
            assertTrue(rendered.image == null || rendered.image!!.startsWith("https://"))
            for (list in listOf("", "$", "a", "list", "a.list[0]", "[", "a[-1]", "a.99999999999999999999", "x".repeat(1000))) {
                ApiRender.render(view.copy(list = ApiList(list, "{@}", "{name}")), root, ctx, maxRows = Int.MAX_VALUE)
            }
        }
        // A broken field among good ones doesn't take the good ones with it.
        val doc = JSONObject().put("name", "pikachu")
        val mixed = ApiRender.render(ApiView(slots = mapOf(ApiSlot.TITLE to "{name}", ApiSlot.VALUE to "{1/0}")), doc, ctx, 0)
        assertEquals("pikachu", mixed.text(ApiSlot.TITLE))
        assertEquals("", mixed.text(ApiSlot.VALUE))
        assertEquals("can't divide by zero", mixed.error)
    }

    @Test
    fun badTimesAreNotTimes() {
        for (value in listOf("garbage", "2026-99-99", "2026-02-30T25:61:00Z", "99999999999999999999999", "-1", "1e400", Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE, Long.MIN_VALUE, "", JSONObject(), JSONArray())) {
            val at = Times.parse(value)
            if (at != null) BuiltInFormulas.evaluate("{countdown(t)} {ago(t)} {date(t)} {time(t)}", JSONObject().put("t", value), ctx)
        }
    }

    @Test
    fun pathsAndSearchesTakeAnyText() {
        val doc = JSONObject().put("a", JSONArray().put(JSONObject().put("b", 1)))
        val weird = listOf("", "$", "@", ".", "..", "[", "]", "[[[[", "['", "[']", "a[", "a[-1]", "a[99999999999999999999]", "a.99999999999999999999",
            "a.-1", "a['b']['c']", "\u0000", "\uD800", "a".repeat(10_000), "a." .repeat(5_000))
        for (path in weird) {
            ApiJson.steps(path)
            ApiJson.valueAt(doc, path)
            ApiJson.ancestors(path)
            ApiJson.items(doc, path, Int.MAX_VALUE)
            ApiJson.items(doc, path, -1)
            ApiJson.children(ApiJson.valueAt(doc, path), path)
        }
        for (root in roots) {
            ApiJson.count(root)
            ApiJson.children(root, "")
            ApiJson.suggestRow(root)
            for (query in listOf("", " ", ".*", "[", "\\", "\u0000", "a".repeat(500), "1")) ApiJson.search(root, query)
        }
    }

    @Test
    fun aCorruptSavedLayoutDropsOnlyThatTile() {
        val good = JSONObject().put("id", 1).put("size", "MEDIUM").put("type", "weather")
        val broken = listOf(
            JSONObject().put("id", 2).put("type", "api"),
            JSONObject().put("id", 3).put("type", "api").put("tile", "not an object"),
            JSONObject().put("id", 4).put("type", "api").put("tile", JSONObject().put("url", 5)),
            JSONObject().put("id", 5).put("type", "api").put("tile", JSONObject().put("url", "https://x.com").put("slots", JSONArray().put(1)).put("list", "rows"))
                .put("refreshMinutes", "often").put("liveSeconds", JSONObject()).put("headers", "X-Key").put("created", "yesterday").put("detail", 7),
            JSONObject().put("id", 6).put("type", "api").put("tile", JSONObject().put("url", "https://x.com").put("slots", JSONObject().put("title", JSONObject().put("x", 1))))
                .put("liveSeconds", Int.MIN_VALUE).put("refreshMinutes", Int.MAX_VALUE).put("size", "ENORMOUS"),
        )
        val read = TileJson.decode(JSONArray().put(good).apply { broken.forEach { put(it) } }.put("junk").put(JSONObject.NULL))
        assertTrue(read.any { it.content == TileContent.Weather })
        // What can be read is repaired; what can't is left out without taking the rest with it.
        read.mapNotNull { it.content as? TileContent.Api }.forEach { api ->
            assertTrue(api.tile.url.startsWith("https://"))
            assertTrue(api.refreshMinutes in 5..60)
        }
    }

    @Test
    fun headerKeysThatCantBeStoredFailQuietly() {
        // Robolectric has no Android keystore: storing and reading must still not throw.
        val secrets = KeystoreApiSecrets(context)
        secrets.put(7, mapOf("X-Api-Key" to "secret"))
        secrets.get(7)
        secrets.remove(7)
        java.io.File(context.noBackupFilesDir, "api_headers.json").writeText("{\"7\": \"not sealed\"}")
        assertEquals(emptyMap<String, String>(), secrets.get(7))
        secrets.remove(7)
    }

    @Test
    fun picturesThatCantBeHadAreLeftOut() {
        for (url in listOf("", "http://x.com/a.png", "javascript:alert(1)", "https://", "https://x.com/" + "a".repeat(5_000), "data:image/png;base64,AAAA")) {
            assertEquals(url, null, ApiStore.image(context, url, 100, 100))
            assertEquals(url, null, ApiStore.image(context, url, 0, -1))
        }
    }

    /** The tile, its expanded view and the editor, shown with everything above. */
    @Test
    fun theScreensShowFailuresCalmly() {
        val view = ApiView(
            "https://api.example.com/x",
            mapOf(ApiSlot.TITLE to "{1/0}", ApiSlot.VALUE to "{countdown('garbage')}", ApiSlot.IMAGE to "{'https://'}", ApiSlot.LINE1 to "{"),
            ApiList("list", "{name}", "{nope(}", "{'https://x.com/a.png'}"),
        )
        val api = TileContent.Api("broken", view, ApiView(slots = mapOf(ApiSlot.LINE2 to "{first(list).name.x}")))
        val states = buildList {
            add(null)
            add(CachedApi("k"))
            ApiProblem.entries.forEach { add(CachedApi("k", failedAt = 1, error = it)) }
            roots.forEach { add(CachedApi("k", it, "x", fetchedAt = ctx.now)) }
            add(CachedApi("k", JSONObject(), "x", fetchedAt = Long.MAX_VALUE, failedAt = Long.MIN_VALUE, error = ApiProblem.OFFLINE))
        }
        var index by androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                val m = rememberTileMetrics(120.dp)
                val cached = states[index]
                Box(Modifier.fillMaxSize()) {
                    Row {
                        for (size in TileSize.entries) {
                            Box(Modifier.size(120.dp)) { ApiFace(size, "broken", view, cached, unlocked = true, m = m, now = ctx.now, picture = { _, _, _ -> null }) }
                        }
                    }
                    ApiDetailBody(api, cached, ctx.now, live = true, paused = false, picture = { _, _, _ -> null })
                }
            }
        }
        for (i in states.indices) {
            index = i
            rule.waitForIdle()
        }
    }

    @Test
    fun theEditorTakesAnyAnswer() {
        var sample by androidx.compose.runtime.mutableStateOf<Any?>(null)
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize()) { ApiEditor(null, {}, {}, sample = sample) }
            }
        }
        for (root in roots) {
            sample = root
            rule.waitForIdle()
            // Search, then open whatever comes first, in every mode.
            for (chip in listOf("title", "list", "row text")) {
                rule.onAllNodesWithText(chip)[0].performScrollTo().performClick()
                if (rule.onAllNodes(hasContentDescription("search fields")).fetchSemanticsNodes().isEmpty()) continue
                rule.onNodeWithContentDescription("search fields").performScrollTo().performTextReplacement("a")
                rule.waitForIdle()
                rule.onNodeWithContentDescription("search fields").performTextReplacement("")
                rule.waitForIdle()
            }
            // A template that can't be read says so, and the tile can't be added.
            rule.onAllNodesWithText("title")[0].performScrollTo().performClick()
        }
        rule.onAllNodesWithText("add tile")[0].performClick()
        rule.waitForIdle()
        assertFalse(rule.onAllNodesWithText("add tile").fetchSemanticsNodes().isEmpty())
    }
}
