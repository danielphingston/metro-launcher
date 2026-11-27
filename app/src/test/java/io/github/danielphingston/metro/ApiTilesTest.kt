package io.github.danielphingston.metro

import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.api.ApiCheck
import io.github.danielphingston.metro.live.api.ApiHttp
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiProblem
import io.github.danielphingston.metro.live.api.ApiRefresh
import io.github.danielphingston.metro.live.api.ApiRender
import io.github.danielphingston.metro.live.api.ApiRequest
import io.github.danielphingston.metro.live.api.ApiResponse
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import io.github.danielphingston.metro.live.api.CachedApi
import io.github.danielphingston.metro.live.api.FormulaContext
import io.github.danielphingston.metro.live.api.Times
import io.github.danielphingston.metro.live.feed.HttpStatusException
import io.github.danielphingston.metro.live.weather.StoredWeather
import io.github.danielphingston.metro.live.weather.WeatherRefresh
import io.github.danielphingston.metro.ui.tiles.ApiTarget
import io.github.danielphingston.metro.ui.tiles.apiTileProblem
import io.github.danielphingston.metro.ui.tiles.templatesOf
import io.github.danielphingston.metro.ui.tiles.viewOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale

/** API tiles: the formula language, reading JSON, the download cache, the layout format and when they refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ApiTilesTest {
    private companion object {
        /** 2026-11-27T07:00:00Z. */
        const val NOW = 1_795_762_800_000L
    }

    private val ctx = FormulaContext(NOW, ZoneOffset.UTC, Locale.US, is24Hour = true)

    private val doc = JSONObject(
        """
        {"event": {"name": "city marathon", "start": "2026-11-27T09:30:00Z", "epoch": 1795771800},
         "temp_c": 21.44, "wind_kmh": 18, "views": 1234567, "live": false, "note": null,
         "odd key": "yes", "tags": ["a", "b"],
         "standings": [
           {"name": "Ana", "points": 120, "avatar": "https://img.example.com/a.png"},
           {"name": "Ben", "points": 96.5},
           {"name": "Cy", "points": "80"}
         ]}
        """,
    )

    private fun eval(template: String, row: Any? = io.github.danielphingston.metro.live.api.ApiFormulas.NO_ROW) =
        BuiltInFormulas.evaluate(template, doc, ctx, row)

    private fun text(template: String) = eval(template).also { assertNull("$template: ${it.error}", it.error) }.text

    private val original = ApiStore.http

    @After
    fun restore() {
        ApiStore.http = original
        ApiStore.clearMemory()
    }

    // ---- formulas ----

    @Test
    fun templatesFillFieldsAndKeepText() {
        assertEquals("city marathon at 09:30", text("{event.name} at {time(event.start)}"))
        assertEquals("21.44 °C", text("{temp_c} °C"))
        assertEquals("yes", text("{['odd key']}"))
        assertEquals("b", text("{tags[1]}"))
        assertEquals("Cy", text("{standings[-1].name}"))
        assertEquals("{literal}", text("{{literal}}"))
        assertEquals("", text("{missing.path}"))
    }

    @Test
    fun arithmeticAndFallbacks() {
        assertEquals("70.6", text("{round(convert(temp_c, 'c', 'f'), 1)}"))
        assertEquals("11.2", text("{round(convert(wind_kmh, 'km/h', 'mph'), 1)}"))
        assertEquals("3", text("{1 + 2}"))
        assertEquals("0.3", text("{0.1 + 0.2}"))
        assertEquals("none", text("{note ?? 'none'}"))
        assertEquals("next", text("{if(live, 'live', 'next')}"))
        assertEquals("1.2M", text("{compact(views)}"))
        assertEquals("1,234,567", text("{thousands(views)}"))
        assertEquals("21.4", text("{fixed(temp_c, 1)}"))
        assertEquals("CITY MARATHON", text("{upper(event.name)}"))
        assertEquals("City Marathon", text("{title(event.name)}"))
    }

    @Test
    fun listFunctions() {
        assertEquals("3", text("{count(standings)}"))
        assertEquals("Ana, Ben, Cy", text("{join(standings, ', ', 'name')}"))
        assertEquals("296.5", text("{sum(standings, 'points')}"))
        assertEquals("Ana", text("{first(standings).name}"))
        assertEquals("Cy", text("{last(standings).name}"))
    }

    @Test
    fun rowTemplatesReadTheItemFirstThenTheDocument() {
        val row = doc.getJSONArray("standings").get(1)
        assertEquals("Ben 96.5 (city marathon)", eval("{name} {points} ({event.name})", row).text)
        assertEquals("Ben", eval("{@.name}", row).text)
        assertEquals("21.44", eval("{\$.temp_c}", row).text)
        assertEquals("b", BuiltInFormulas.evaluate("{@}", doc, ctx, "b").text)
    }

    @Test
    fun countdownsTickOnThePhoneAndSayWhenTheyChange() {
        // 2 h 30 m before the start: shown to the minute, next change at the next minute boundary.
        val e = eval("{countdown(event.start)}")
        assertEquals("2h 30m", e.text)
        assertEquals(NOW + 60_000, e.changesAt)
        // Epoch seconds read the same.
        assertEquals("2h 30m", text("{countdown(event.epoch)}"))
        // In the last hour, to the second.
        val close = BuiltInFormulas.evaluate("{countdown(event.start)}", doc, ctx.copy(now = NOW + 2 * 3_600_000L + 29 * 60_000L + 30_500L))
        assertEquals("0:30", close.text)
        assertEquals(1_795_771_800_000L - 29_000L, close.changesAt)
        // Past: zero, and no more changes.
        val past = BuiltInFormulas.evaluate("{countdown(event.start)}", doc, ctx.copy(now = NOW + 3 * 3_600_000L))
        assertEquals("0:00", past.text)
        assertNull(past.changesAt)
        // Text without time functions never changes by itself.
        assertNull(eval("{event.name}").changesAt)
    }

    @Test
    fun timesInZonesAndFormats() {
        assertEquals(1795771800000L, Times.parse("2026-11-27T09:30:00Z"))
        assertEquals(1795771800000L, Times.parse("2026-11-27T15:00:00+05:30"))
        assertEquals(1795771800000L, Times.parse("2026-11-27T09:30:00.000+0000"))
        assertEquals(1795771800000L, Times.parse("2026-11-27 09:30:00"))
        assertEquals(1795771800000L, Times.parse(1795771800))
        assertEquals(1795771800000L, Times.parse(1795771800000L))
        assertEquals(1795771800000L, Times.parse("1795771800"))
        assertNull(Times.parse("soon"))
        assertEquals("15:00", text("{time(event.start, 'Asia/Kolkata')}"))
        assertEquals("Fri 27 Nov", text("{date(event.start)}"))
        assertEquals("9:30 AM", BuiltInFormulas.evaluate("{time(event.start)}", doc, ctx.copy(is24Hour = false)).text)
    }

    @Test
    fun problemsAreSaidNotThrown() {
        assertNotNull(BuiltInFormulas.problem("{unknown(x)}"))
        assertNotNull(BuiltInFormulas.problem("{a"))
        assertNotNull(BuiltInFormulas.problem("a}"))
        assertNotNull(BuiltInFormulas.problem("{}"))
        assertNotNull(eval("{temp_c / 0}").error)
        assertNotNull(eval("{convert(1, 'c', 'kg')}").error)
        assertNotNull(eval("{time(event.name)}").error)
        assertNotNull(BuiltInFormulas.problem("x".repeat(BuiltInFormulas.MAX_TEMPLATE + 1)))
        // Deep nesting is refused, not a stack overflow.
        assertNotNull(BuiltInFormulas.problem("{" + "(".repeat(200) + "1" + ")".repeat(200) + "}"))
        // A good part still shows next to a bad one.
        val mixed = eval("{event.name} {nope(1)}")
        assertEquals("", mixed.text)
        assertNotNull(mixed.error)
    }

    // ---- JSON ----

    @Test
    fun parsingRefusesWhatIsntJson() {
        assertEquals(3, (ApiJson.parse("[1,2,3]") as JSONArray).length())
        assertEquals(2, (ApiJson.parse("﻿{\"a\":1,\"b\":2}") as JSONObject).length())
        for (bad in listOf("<html><body>error</body></html>", "{\"a\":1} trailing", "[".repeat(100) + "]".repeat(100))) {
            try {
                ApiJson.parse(bad)
                throw AssertionError("parsed $bad")
            } catch (_: JSONException) {
            }
        }
    }

    @Test
    fun fieldsListPathsAFormulaReads() {
        val fields = ApiJson.fields(doc)
        val paths = fields.map { it.path }
        assertTrue("event.name" in paths)
        assertTrue("['odd key']" in paths)
        assertTrue("standings[0].points" in paths)
        assertTrue(fields.first { it.path == "standings" }.list)
        assertEquals(3, fields.first { it.path == "standings" }.count)
        // Every listed path reads back its value.
        fields.filterNot { it.list }.forEach { assertNull(it.path, eval("{${it.path}}").error) }
        assertEquals(listOf("name", "points", "avatar"), ApiJson.rowFields(doc, "standings").map { it.path })
        assertEquals(listOf("@"), ApiJson.rowFields(doc, "tags").map { it.path })
        assertEquals("$", ApiJson.fields(JSONArray("[{\"a\":1}]")).first().path)
    }

    @Test
    fun renderFillsSlotsRowsAndFallsBackToTheTile() {
        val tile = ApiView(
            "https://example.com/a.json",
            mapOf(ApiSlot.TITLE to "{event.name}", ApiSlot.VALUE to "{countdown(event.start)}", ApiSlot.IMAGE to "{standings[0].avatar}"),
            ApiList("standings", "{name}", "{points} pts", "{avatar}"),
        )
        val r = ApiRender.render(tile, doc, ctx, 2)
        assertEquals("city marathon", r.text(ApiSlot.TITLE))
        assertEquals("2h 30m", r.text(ApiSlot.VALUE))
        assertEquals("https://img.example.com/a.png", r.image)
        assertEquals(2, r.rows.size)
        assertEquals("96.5 pts", r.rows[1].secondary)
        assertNull(r.rows[1].image)
        assertEquals(NOW + 60_000, r.changesAt)
        val detail = ApiRender.render(ApiView(slots = mapOf(ApiSlot.TITLE to "{upper(event.name)}")), doc, ctx, 50, fallback = tile)
        assertEquals("CITY MARATHON", detail.text(ApiSlot.TITLE))
        assertEquals("2h 30m", detail.text(ApiSlot.VALUE))
        assertEquals(3, detail.rows.size)
        // Only https pictures and links.
        val insecure = ApiRender.render(ApiView(slots = mapOf(ApiSlot.IMAGE to "http://x.com/a.png", ApiSlot.LINK to "javascript:alert(1)")), doc, ctx, 0)
        assertNull(insecure.image)
        assertNull(insecure.link)
    }

    // ---- the store ----

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun conditionalRequestsKeepTheCopyAndFailuresKeepItStale() = runBlocking {
        val sent = ArrayList<Map<String, String>>()
        var answer: () -> ApiResponse = { ApiResponse("{\"v\":1}".toByteArray(), etag = "\"e1\"") }
        ApiStore.http = ApiHttp { _, headers, _ ->
            sent += headers
            answer()
        }
        val request = ApiRequest("https://api.example.com/v.json", mapOf("X-Api-Key" to "secret"))
        ApiStore.refresh(context, request, maxAgeMs = 60_000)
        assertEquals("secret", sent.last()["X-Api-Key"])
        assertNull(sent.last()["If-None-Match"])
        assertEquals(1, (ApiStore.data.value[request.key]!!.root as JSONObject).getInt("v"))
        // Fresh: no download.
        ApiStore.refresh(context, request, maxAgeMs = 60_000)
        assertEquals(1, sent.size)
        // Forced: asks with the ETag; 304 keeps the copy.
        answer = { ApiResponse(ByteArray(0), notModified = true) }
        ApiStore.refresh(context, request, maxAgeMs = 60_000, force = true)
        assertEquals("\"e1\"", sent.last()["If-None-Match"])
        assertEquals(1, (ApiStore.data.value[request.key]!!.root as JSONObject).getInt("v"))
        // A failure keeps the data and marks it.
        answer = { throw HttpStatusException(503) }
        ApiStore.refresh(context, request, maxAgeMs = 60_000, force = true)
        val cached = ApiStore.data.value[request.key]!!
        assertTrue(cached.failing)
        assertTrue(cached.hasData)
        assertEquals(ApiProblem.SERVER, cached.error)
        // The copy on disk holds the body, not the key.
        val saved = java.io.File(context.noBackupFilesDir, "api").listFiles()!!.joinToString { it.readText() }
        assertFalse("secret" in saved)
        // A restart reads the copy back.
        ApiStore.clearMemory()
        ApiStore.load(context, request)
        assertEquals(1, (ApiStore.data.value[request.key]!!.root as JSONObject).getInt("v"))
    }

    @Test
    fun checkSaysWhatIsWrong() = runBlocking {
        ApiStore.http = ApiHttp { url, _, _ ->
            when {
                "html" in url -> ApiResponse("<html></html>".toByteArray())
                "key" in url -> throw HttpStatusException(401)
                else -> ApiResponse("[1]".toByteArray())
            }
        }
        assertTrue(ApiStore.check(context, ApiRequest("https://a.com/ok")) is ApiCheck.Ok)
        assertEquals(ApiProblem.NOT_JSON, (ApiStore.check(context, ApiRequest("https://a.com/html")) as ApiCheck.Failed).problem)
        assertEquals(ApiProblem.REFUSED, (ApiStore.check(context, ApiRequest("https://a.com/key")) as ApiCheck.Failed).problem)
        assertEquals(ApiProblem.BAD_ADDRESS, (ApiStore.check(context, ApiRequest("file:///etc/hosts")) as ApiCheck.Failed).problem)
    }

    @Test
    fun addressesAreHttpsOnly() {
        assertEquals("https://api.example.com/x", ApiStore.address("http://api.example.com/x"))
        assertEquals("https://api.example.com/x", ApiStore.address("api.example.com/x"))
        assertEquals("https://api.example.com/x", ApiStore.address("https://me:pw@api.example.com/x"))
        assertNull(ApiStore.address("file:///sdcard/a.json"))
        assertNull(ApiStore.address("content://x/y"))
        assertNull(ApiStore.address("javascript:alert(1)"))
        assertNull(ApiStore.address("https://a b.com"))
        assertNull(ApiStore.address("https://" + "a".repeat(3000) + ".com"))
    }

    @Test
    fun differentKeysAreDifferentCopies() {
        assertFalse(ApiRequest("https://a.com", mapOf("K" to "1")).key == ApiRequest("https://a.com", mapOf("K" to "2")).key)
        assertEquals(ApiRequest("https://a.com", mapOf("K" to "1", "L" to "2")).key, ApiRequest("https://a.com", mapOf("L" to "2", "K" to "1")).key)
        assertFalse("secret" in ApiRequest("https://a.com", mapOf("K" to "secret")).toString())
    }

    // ---- the layout ----

    private val api = TileContent.Api(
        name = "marathon",
        tile = ApiView("https://api.example.com/race.json", mapOf(ApiSlot.VALUE to "{countdown(event.start)}"), ApiList("standings", "{name}", "{points}")),
        detail = ApiView("https://api.example.com/live.json", mapOf(ApiSlot.TITLE to "{event.name}")),
        headers = listOf("X-Api-Key"),
        refreshMinutes = 10,
        liveSeconds = 15,
        wifiOnly = true,
        created = 42,
    )

    @Test
    fun layoutRoundTripsWithoutSecrets() {
        val json = TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, api)))
        assertFalse("secret" in json.toString())
        assertEquals(api, TileJson.decode(JSONArray(json.toString())).single().content)
    }

    @Test
    fun aCraftedLayoutIsCheckedAgain() {
        val obj = TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, api))).getJSONObject(0)
        obj.put("refreshMinutes", 0).put("liveSeconds", 1)
        obj.put("headers", JSONArray(listOf("X-Api-Key", "Host", "bad header", "Cookie")))
        obj.getJSONObject("tile").getJSONObject("slots").put("value", "x".repeat(5000)).put("nonsense", "y")
        obj.getJSONObject("detail").put("url", "http://plain.example.com/x")
        val read = TileJson.decode(JSONArray().put(obj)).single().content as TileContent.Api
        assertEquals(5, read.refreshMinutes)
        assertEquals(5, read.liveSeconds)
        assertEquals(listOf("X-Api-Key", "Cookie"), read.headers)
        assertEquals(BuiltInFormulas.MAX_TEMPLATE, read.tile.slot(ApiSlot.VALUE).length)
        assertEquals("https://plain.example.com/x", read.detail.url)
        // An address that can't be https drops the tile.
        obj.getJSONObject("tile").put("url", "file:///data/data/x")
        assertTrue(TileJson.decode(JSONArray().put(obj)).isEmpty())
    }

    @Test
    fun anOlderVersionKeepsTheTile() {
        // Older versions read "api" as an unknown type and write it back as it was; this build reads it.
        val json = TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, api)))
        val read = TileJson.read(json.toString())!!
        assertEquals(1, read.tiles.size)
    }

    @Test
    fun editorTemplatesRoundTrip() {
        assertEquals(api.tile, viewOf(api.tile.url, templatesOf(api.tile)))
        val t = templatesOf(api.tile)
        assertNull(apiTileProblem(api.tile.url, "", t, emptyMap(), listOf("X-Api-Key" to "v")))
        assertNotNull(apiTileProblem("ftp://x", "", t, emptyMap(), emptyList()))
        assertNotNull(apiTileProblem(api.tile.url, "", emptyMap(), emptyMap(), emptyList()))
        assertNotNull(apiTileProblem(api.tile.url, "", t, emptyMap(), listOf("Host" to "v")))
        assertNotNull(apiTileProblem(api.tile.url, "", t, emptyMap(), listOf("X-Api-Key" to "")))
        assertNotNull(apiTileProblem(api.tile.url, "", t + (ApiTarget.TITLE to "{bad("), emptyMap(), emptyList()))
    }

    // ---- when they refresh ----

    @Test
    fun refreshTimesAlignAndBackOff() {
        val interval = ApiRefresh.tileInterval(15, batterySaver = false)
        assertEquals(30 * 60_000L, ApiRefresh.tileInterval(15, batterySaver = true))
        val fetched = CachedApi("k", JSONObject(), "{}", fetchedAt = NOW + 12_345)
        val wake = ApiRefresh.nextWake(fetched, interval, NOW + 20_000)
        assertEquals(0L, wake % 60_000)
        assertTrue(wake >= fetched.fetchedAt + interval - ApiRefresh.TOLERANCE_MS)
        assertTrue(wake < fetched.fetchedAt + interval + 60_000)
        val failed = fetched.copy(failedAt = NOW + 100_000, error = ApiProblem.OFFLINE)
        assertTrue(ApiRefresh.nextWake(failed, interval, NOW + 100_000) <= NOW + 100_000 + ApiRefresh.RETRY_MS + 60_000)
        assertEquals(15_000L, ApiRefresh.liveInterval(15, false))
        assertEquals(45_000L, ApiRefresh.liveInterval(15, true))
        assertEquals(30_000L, ApiRefresh.liveInterval(5, true))
        assertEquals(30_000L, ApiRefresh.liveDelay(15_000, 1))
        assertEquals(ApiRefresh.RETRY_MS, ApiRefresh.liveDelay(15_000, 10))
        assertFalse(ApiRefresh.livePolling(0, false, false))
        assertFalse(ApiRefresh.livePolling(15, wifiOnly = true, metered = true))
        assertTrue(ApiRefresh.livePolling(15, wifiOnly = true, metered = false))
        assertTrue(ApiStore.isStale(null, interval))
        assertFalse(ApiStore.isStale(fetched, interval, now = NOW + 60_000))
        assertTrue(ApiStore.isStale(fetched, interval, now = fetched.fetchedAt + interval))
    }

    @Test
    fun weatherRefreshesOnlyWhenDue() {
        val stored = StoredWeather(forecastJson = "{}", fetchedAt = NOW)
        val next = WeatherRefresh.nextCheck(stored, lastFailure = 0, now = NOW + 1_000)
        assertEquals(NOW + 60 * 60_000L, next)
        assertEquals(NOW + 60 * 60_000L + WeatherRefresh.RETRY_MS, WeatherRefresh.nextCheck(stored, lastFailure = NOW + 60 * 60_000L, now = NOW + 60 * 60_000L))
        assertEquals(NOW + 60_000L, WeatherRefresh.nextCheck(StoredWeather(), 0, NOW + 1))
    }
}
