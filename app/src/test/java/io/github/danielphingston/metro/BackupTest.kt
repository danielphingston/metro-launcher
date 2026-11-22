package io.github.danielphingston.metro

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.BackupException
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.LaunchStyle
import io.github.danielphingston.metro.data.InkCodec
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.InkStroke
import io.github.danielphingston.metro.data.NoteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The compatibility gate for backups: every build must keep importing every backup format ever
 * shipped. Each `src/test/resources/backup/format-N.json` is a permanent fixture, never edited or
 * deleted; a new format adds a new file.
 *
 * `format-1.json` is a realistic export from 0.7.16: light theme, a non-default accent, 6 columns,
 * monochrome icons, TURNSTILE launch style, eight tiles (apps, a wide weather tile, a folder with
 * two apps, a quick setting, a photos tile with a custom span), two picked photos, a Pune weather
 * location, two search keywords, a string set, and one key this build doesn't know
 * (`future_setting_from_newer_build`) that must survive the import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupTest {
    private fun fixtures(): List<File> {
        val dir = File(checkNotNull(javaClass.classLoader.getResource("backup")) { "no backup fixtures" }.toURI())
        return dir.listFiles { f -> f.name.matches(Regex("format-\\d+\\.json")) }.orEmpty().sortedBy { it.name }
    }

    private fun message(block: () -> Unit): String? = try {
        block(); null
    } catch (e: BackupException) {
        e.message
    }

    @Test
    fun roundTripKeepsEveryType() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("s") to "text \"quoted\"\nline",
            intPreferencesKey("i") to -5,
            longPreferencesKey("l") to 9_000_000_000L,
            booleanPreferencesKey("b") to true,
            floatPreferencesKey("f") to 1.5f,
            doublePreferencesKey("d") to 18.5204,
            stringSetPreferencesKey("set") to setOf("a", "b"),
            stringPreferencesKey("weather_forecast") to "big json",
            longPreferencesKey("weather_fetched_at") to 1L,
            stringPreferencesKey("assistant_last_question") to "private",
            longPreferencesKey("assistant_last_asked_at") to 2L,
        )
        val json = Backup.encode(prefs, "1.2.3", 1234L)
        assertFalse(json.contains("big json") || json.contains("private"))
        val decoded = Backup.decode(json)
        val expected = prefs.asMap().filterKeys { it.name !in Backup.EXCLUDED_KEYS }
        assertEquals(expected, decoded.asMap())
        assertEquals(1234L, org.json.JSONObject(json).getLong("exportedAt"))
        assertEquals("1.2.3", org.json.JSONObject(json).getString("appVersion"))
    }

    @Test
    fun everyShippedFormatStillImports() {
        val files = fixtures()
        assertTrue("no fixtures found", files.isNotEmpty())
        assertTrue("format-1.json must exist", files.any { it.name == "format-1.json" })
        assertTrue("a fixture of the current format must exist", files.any { it.name == "format-${Backup.FORMAT}.json" })
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        for (file in files) {
            val prefs = try {
                Backup.decode(file.readText())
            } catch (e: Exception) {
                throw AssertionError("${file.name} no longer imports", e)
            }
            val settings = repository.settingsOf(prefs)
            // These expectations describe the fixture; format-1.json must always read this way.
            if (file.name == "format-1.json") {
                assertEquals(ThemeMode.LIGHT, settings.theme)
                assertEquals(-16746753, settings.accent)
                assertEquals(6, settings.columns)
                assertTrue(settings.monochromeIcons)
                assertEquals(LaunchStyle.TURNSTILE, settings.launchStyle)
                assertEquals(setOf("com.example.shop", "com.example.notes"), settings.searchInOtherApps)
                assertEquals(listOf("w", "yt"), settings.searchKeywords.map { it.keyword })
                val tiles = TileJson.read(checkNotNull(prefs[stringPreferencesKey("tiles")]))
                assertNotNull(tiles)
                val read = tiles!!
                assertEquals(6, read.tiles.size)
                assertTrue(read.unread.top.isEmpty())
                assertTrue(read.tiles.any { it.content is TileContent.Weather })
                assertTrue(read.tiles.any { it.content is TileContent.Photos })
                val folder = read.tiles.map { it.content }.filterIsInstance<TileContent.Folder>().single()
                assertEquals(2, folder.children.size)
                assertEquals("Pune", prefs[stringPreferencesKey("weather_name")])
                assertEquals(18.5204, prefs[doublePreferencesKey("weather_latitude")]!!, 0.0)
                assertEquals("kept", prefs[stringPreferencesKey("future_setting_from_newer_build")])
            }
            // format-2.json is an export from 0.9.0: tool tiles, and a handwriting tile whose drawing is in "ink".
            if (file.name == "format-2.json") {
                val contents = Backup.decodeContents(file.readText())
                val tiles = TileJson.read(checkNotNull(prefs[stringPreferencesKey("tiles")]))!!.tiles
                val ink = tiles.map { it.content }.filterIsInstance<TileContent.Notes>().single { it.style == NoteStyle.INK }
                assertEquals("ink-1763700000000-42", ink.inkId)
                assertEquals(0xFFE53935.toInt(), ink.penColor)
                assertTrue(ink.penOnTile)
                val drawing = InkCodec.decode(checkNotNull(contents.ink[ink.inkId]))
                assertEquals(listOf(30, 20), drawing.map { it.points.size })
                assertEquals(listOf(InkStroke.PLAIN, 0xFFE53935.toInt()), drawing.map { it.color })
                val note = tiles.map { it.content }.filterIsInstance<TileContent.Notes>().single { it.style == NoteStyle.NOTE }
                assertEquals("shop closes at 9", note.text)
                assertEquals(2, note.items.size)
                assertEquals(listOf(480, 1200), tiles.map { it.content }.filterIsInstance<TileContent.Routine>().single().times)
                assertTrue(tiles.any { it.content == TileContent.Dice(2, 6, 1763700000003) })
                // And its drawings survive a re-export too.
                val again = Backup.decodeContents(Backup.encode(prefs, "x", 0, contents.ink))
                assertTrue(contents.ink.keys == again.ink.keys && contents.ink.all { (id, bytes) -> again.ink[id]!!.contentEquals(bytes) })
            }
            // And a re-export of what was read must itself import to the same thing.
            assertEquals(prefs.asMap(), Backup.decode(Backup.encode(prefs, "x", 0)).asMap())
        }
    }

    @Test
    fun newerFormatIsRejected() {
        val json = """{"app":"metro-launcher","format":${Backup.FORMAT + 1},"prefs":{}}"""
        assertEquals(
            "This backup is from a newer version of Metro. Update the app to restore it.",
            message { Backup.decode(json) },
        )
    }

    @Test
    fun otherFilesAreRejected() {
        assertEquals("This file is not a Metro backup.", message { Backup.decode("""{"app":"other","format":1,"prefs":{}}""") })
        assertEquals("This file is not a Metro backup.", message { Backup.decode("not json at all") })
        assertEquals("This file is not a Metro backup.", message { Backup.decode("""{"app":"metro-launcher","prefs":{}}""") })
        assertEquals("This file is not a Metro backup.", message { Backup.decode("[]") })
    }

    @Test
    fun malformedEntryIsReported() {
        val json = """{"app":"metro-launcher","format":1,"prefs":{"columns":{"type":"int","value":"many"}}}"""
        assertTrue(message { Backup.decode(json) }!!.contains("columns"))
        val noObject = """{"app":"metro-launcher","format":1,"prefs":{"columns":5}}"""
        assertTrue(message { Backup.decode(noObject) }!!.contains("columns"))
    }

    @Test
    fun unknownKeyIsKeptAndUnknownTypeIsSkipped() {
        val json = """{"app":"metro-launcher","format":1,"prefs":{
            "brand_new_key":{"type":"string","value":"hello"},
            "from_the_future":{"type":"quaternion","value":[1,2,3,4]},
            "columns":{"type":"int","value":4},
            "weather_forecast":{"type":"string","value":"ignored"}}}"""
        val prefs = Backup.decode(json)
        assertEquals("hello", prefs[stringPreferencesKey("brand_new_key")])
        assertEquals(4, prefs[intPreferencesKey("columns")])
        assertEquals(setOf("brand_new_key", "columns"), prefs.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun wronglyTypedWeatherKeysAreRejected() {
        val wrong = "This backup is damaged: it holds a setting of the wrong kind."
        val intLatitude = mutablePreferencesOf(intPreferencesKey("weather_latitude") to 18)
        assertEquals(wrong, message { Backup.checkKeyTypes(intLatitude) })
        val stringFahrenheit = mutablePreferencesOf(stringPreferencesKey("weather_fahrenheit") to "yes")
        assertEquals(wrong, message { Backup.checkKeyTypes(stringFahrenheit) })
        val intTiles = mutablePreferencesOf(intPreferencesKey("tiles") to 1)
        assertEquals(wrong, message { Backup.checkKeyTypes(intTiles) })
        // Through decode as well: a hand-edited file whose latitude is an int.
        val json = """{"app":"metro-launcher","format":1,"prefs":{"weather_latitude":{"type":"int","value":18}}}"""
        assertEquals(wrong, message { Backup.checkKeyTypes(Backup.decode(json)) })
    }

    @Test
    fun correctlyTypedWeatherKeysPass() {
        val prefs = mutablePreferencesOf(
            doublePreferencesKey("weather_latitude") to 18.5204,
            doublePreferencesKey("weather_longitude") to 73.8567,
            booleanPreferencesKey("weather_fahrenheit") to true,
            stringPreferencesKey("weather_name") to "Pune",
            stringPreferencesKey("tiles") to "[]",
            intPreferencesKey("columns") to 4,
        )
        assertEquals(null, message { Backup.checkKeyTypes(prefs) })
        for (file in fixtures()) {
            assertEquals(file.name, null, message { Backup.checkKeyTypes(Backup.decode(file.readText())) })
        }
    }

    @Test
    fun oldBackupsHaveNoLemmyAppAndNoFeedTile() {
        // Exported before the feed tile: posts open in the browser, and the layout reads unchanged.
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        for (file in fixtures()) {
            val prefs = Backup.decode(file.readText())
            assertEquals(file.name, null, repository.settingsOf(prefs).lemmyApp)
            val tiles = prefs[stringPreferencesKey("tiles")]?.let { TileJson.read(it) }
            assertTrue(file.name, tiles?.tiles.orEmpty().none { it.content is TileContent.Feed })
        }
    }

    @Test
    fun feedTilesAndTheLemmyAppRoundTrip() {
        val lemmy = TileContent.Feed(listOf("https://programming.dev/feeds/c/programming.xml?sort=Hot", "https://lemmy.ml/feeds/c/linux.xml?sort=Hot"))
        val rss = TileContent.Feed(listOf("https://example.com/feed.xml"))
        val tiles = TileJson.encode(
            listOf(
                io.github.danielphingston.metro.data.TileSpec(1, io.github.danielphingston.metro.data.TileSize.WIDE, lemmy, 0, 0),
                io.github.danielphingston.metro.data.TileSpec(2, io.github.danielphingston.metro.data.TileSize.MEDIUM, rss, 0, 2),
            ),
        ).toString()
        val prefs = mutablePreferencesOf(stringPreferencesKey("tiles") to tiles, stringPreferencesKey("lemmy_app") to "com.jerboa")
        val restored = Backup.decode(Backup.encode(prefs, "test", 0L))
        assertEquals("com.jerboa", SettingsRepository(RuntimeEnvironment.getApplication()).settingsOf(restored).lemmyApp)
        assertEquals(listOf(lemmy, rss), TileJson.read(restored[stringPreferencesKey("tiles")]!!)!!.tiles.map { it.content })
        assertEquals(null, message { Backup.checkKeyTypes(restored) })
    }

    @Test
    fun aWronglyTypedLemmyAppIsCaughtBeforeImport() {
        // Backup.import reads the settings first and turns this into "damaged" instead of restoring a launcher that crashes.
        val json = """{"app":"metro-launcher","format":1,"prefs":{"lemmy_app":{"type":"int","value":3}}}"""
        val restored = Backup.decode(json)
        val threw = try {
            SettingsRepository(RuntimeEnvironment.getApplication()).settingsOf(restored)
            false
        } catch (_: ClassCastException) {
            true
        }
        assertTrue(threw)
    }

    @Test
    fun formatOneNeedsNoMigration() {
        val root = org.json.JSONObject("""{"app":"metro-launcher","format":1,"prefs":{}}""")
        assertTrue(Backup.migrate(1, root) === root)
    }

    @Test
    fun handwritingTravelsWithTheBackupAndDamagedInkStopsTheRestore() {
        val drawing = InkCodec.encode(listOf(InkStroke(listOf(InkPoint(0.1f, 0.1f, 0.5f), InkPoint(0.3f, 0.3f, 0.5f)), 0xFF43A047.toInt())))
        val json = Backup.encode(mutablePreferencesOf(stringPreferencesKey("tiles") to "[]"), "x", 0, mapOf("ink-1-1" to drawing))
        assertEquals(Backup.FORMAT, org.json.JSONObject(json).getInt("format"))
        assertTrue(Backup.decodeContents(json).ink.getValue("ink-1-1").contentEquals(drawing))
        // A format 1 backup has no drawings and needs none.
        assertTrue(Backup.decodeContents("""{"app":"metro-launcher","format":1,"prefs":{}}""").ink.isEmpty())
        // Not a drawing, not Base64, or an id that could leave the ink folder: the restore stops before anything changes.
        val bad = listOf(
            """{"app":"metro-launcher","format":2,"prefs":{},"ink":{"ink-1":"bm90IGluaw=="}}""",
            """{"app":"metro-launcher","format":2,"prefs":{},"ink":{"ink-1":"%%%"}}""",
            """{"app":"metro-launcher","format":2,"prefs":{},"ink":{"../x":"${java.util.Base64.getEncoder().encodeToString(drawing)}"}}""",
        )
        bad.forEach { assertEquals("This backup is damaged: a drawing in it can't be read.", message { Backup.decodeContents(it) }) }
    }
}
