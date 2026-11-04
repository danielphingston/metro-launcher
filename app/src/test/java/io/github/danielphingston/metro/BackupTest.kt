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
    fun formatOneNeedsNoMigration() {
        val root = org.json.JSONObject("""{"app":"metro-launcher","format":1,"prefs":{}}""")
        assertTrue(Backup.migrate(1, root) === root)
    }
}
