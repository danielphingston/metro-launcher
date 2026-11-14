package io.github.danielphingston.metro

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.LayoutOps
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.LayoutStartSettings
import io.github.danielphingston.metro.data.LayoutsJson
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.search.LauncherActions
import io.github.danielphingston.metro.ui.search.LockedLayoutsProvider
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.start.TileKeys
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Each start layout's own settings (see [LayoutStartSettings]), and where layouts sit in settings and search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LayoutStartSettingsTest {
    private val columns = intPreferencesKey("columns")
    private val arrows = booleanPreferencesKey("accent_arrows")
    private val background = stringPreferencesKey("start_background")
    private val photo = stringPreferencesKey("background_photo")
    private val photoAccent = intPreferencesKey("background_accent")
    private val topPadding = intPreferencesKey("start_top_padding")
    private val theme = stringPreferencesKey("theme")

    private fun app(id: Long, pkg: String) = TileSpec(id, TileSize.MEDIUM, TileContent.App(AppKey(pkg, "$pkg.Main", 0)), 0, 0)

    private val work = mapOf("columns" to 6, "start_background" to "PHOTO_FULL", "background_photo" to "content://work", "accent_arrows" to true)

    @Test
    fun theKeyListIsTheStartPageAndTheBackground() {
        assertEquals(
            listOf(
                "columns", "start_top_padding", "start_bottom_padding", "start_left_padding", "start_right_padding", "accent_arrows",
                "start_background", "background_photo", "background_accent", "tile_transparency", "accent_from_background",
                "photo_moves_with_tiles", "tile_colour_mode", "tile_icon_colour_vividness",
            ),
            LayoutStartSettings.NAMES,
        )
        // The names are the settings' own keys, not copies of them.
        assertTrue(LayoutStartSettings.SPECS.any { it.key == SettingsRepository.Keys.columns })
    }

    @Test
    fun settingsKeepTheirTypesThroughJson() {
        val values = mapOf("columns" to 6, "background_accent" to 0xFF336699.toInt(), "accent_arrows" to true, "start_background" to "PHOTO_TILES")
        val decoded = LayoutStartSettings.decode(JSONObject(LayoutStartSettings.encode(values).toString()))!!
        assertEquals(values, decoded)
        assertTrue(decoded["columns"] is Int)
        assertTrue(decoded["background_accent"] is Int)
        assertTrue(decoded["accent_arrows"] is Boolean)
        assertTrue(decoded["start_background"] is String)
    }

    @Test
    fun unknownKeysAndWrongTypesAreIgnoredAndNoObjectMeansNoChange() {
        val json = JSONObject("""{"columns":"six","accent_arrows":1,"theme":"LIGHT","from_the_future":3,"start_top_padding":12,"tile_transparency":1.5,"background_accent":99999999999}""")
        assertEquals(mapOf("start_top_padding" to 12), LayoutStartSettings.decode(json))
        assertNull(LayoutStartSettings.decode(null))
        assertEquals(emptyMap<String, Any>(), LayoutStartSettings.decode(JSONObject()))
    }

    @Test
    fun layoutsCarrySettingsOnlyWhenTheyHaveThem() {
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", "[]", work)))
        val text = LayoutsJson.encode(set)
        val root = JSONObject(text).getJSONArray("layouts")
        assertFalse(root.getJSONObject(0).has("settings"))
        assertEquals(6, root.getJSONObject(1).getJSONObject("settings").getInt("columns"))
        assertEquals(set, LayoutsJson.decode(text))
        // A layout written before settings existed reads as one without them.
        assertNull(LayoutsJson.decode("""{"active":1,"layouts":[{"id":1,"name":"a","tiles":[]}]}""")!!.byId(1)!!.settings)
        // A settings value that isn't an object is no settings, not a broken layout.
        assertNull(LayoutsJson.decode("""{"active":1,"layouts":[{"id":1,"name":"a","tiles":[],"settings":3}]}""")!!.byId(1)!!.settings)
    }

    @Test
    fun readAndApplyTouchOnlyThePerLayoutKeysWithTheirTypes() {
        val prefs = mutablePreferencesOf(columns to 4, background to "SOLID", theme to "LIGHT", topPadding to 20)
        assertEquals(mapOf("columns" to 4, "start_background" to "SOLID", "start_top_padding" to 20), LayoutStartSettings.read(prefs))
        LayoutStartSettings.apply(prefs, work)
        assertEquals(6, prefs[columns])
        assertEquals(true, prefs[arrows])
        assertEquals("PHOTO_FULL", prefs[background])
        assertEquals("content://work", prefs[photo])
        // What the layout doesn't set goes back to its default; other settings stay.
        assertNull(prefs[topPadding])
        assertEquals("LIGHT", prefs[theme])
        // A value of the wrong type under a per-layout key (a damaged backup) is skipped, not thrown.
        val damaged = mutablePreferencesOf(stringPreferencesKey("columns") to "6")
        assertEquals(emptyMap<String, Any>(), LayoutStartSettings.read(damaged))
    }

    @Test
    fun switchingWritesTheLiveSettingsBackAndLoadsTheTargets() {
        val a = "[]"
        val b = "[]"
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", a, mapOf("columns" to 4)), SavedLayout(2, "work", b, work)))
        val prefs = mutablePreferencesOf(columns to 8, topPadding to 40, photoAccent to 7, theme to "DARK")
        val switched = LayoutOps.switchStore(prefs, set, a, 2)!!
        assertEquals(2L, switched.active)
        // The live values went into the layout that was on screen...
        assertEquals(mapOf("columns" to 8, "start_top_padding" to 40, "background_accent" to 7), switched.byId(1)!!.settings)
        // ...the target's became the live ones, and what it doesn't set was removed.
        assertEquals(6, prefs[columns])
        assertEquals("content://work", prefs[photo])
        assertNull(prefs[topPadding])
        assertNull(prefs[photoAccent])
        assertEquals("DARK", prefs[theme])
        assertEquals(switched, LayoutsJson.decode(prefs[stringPreferencesKey(LayoutsJson.KEY)]))
        // And back again restores the first layout's settings exactly.
        val back = LayoutOps.switchStore(prefs, switched, b, 1)!!
        assertEquals(8, prefs[columns])
        assertEquals(40, prefs[topPadding])
        assertEquals(7, prefs[photoAccent])
        assertNull(prefs[photo])
        assertEquals(work, back.byId(2)!!.settings)
    }

    @Test
    fun aLayoutWithoutSettingsLeavesTheLiveOnesAndGetsThemWrittenBack() {
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "old", "[]")))
        val prefs = mutablePreferencesOf(columns to 6, arrows to true)
        val switched = LayoutOps.switchStore(prefs, set, "[]", 2)!!
        assertEquals(6, prefs[columns])
        assertEquals(true, prefs[arrows])
        assertNull(switched.byId(2)!!.settings)
        assertEquals(mapOf("columns" to 6, "accent_arrows" to true), switched.byId(1)!!.settings)
        // Edited while on screen, then left: the old entry is filled in now.
        prefs[columns] = 4
        val back = LayoutOps.switchStore(prefs, switched, "[]", 1)!!
        assertEquals(mapOf("columns" to 4, "accent_arrows" to true), back.byId(2)!!.settings)
        // Without live settings given, a write-back keeps what the entry had.
        assertEquals(switched.byId(1)!!.settings, LayoutOps.switch(switched, "[]", 1)!!.byId(1)!!.settings)
    }

    @Test
    fun savingAndDuplicatingCopyTheSettings() {
        val live = mapOf("columns" to 6, "background_photo" to "content://p")
        val first = LayoutOps.saveAs(null, "[]", "work", live)!!
        assertEquals(listOf(live, live), first.layouts.map { it.settings })
        val more = LayoutOps.saveAs(first.copy(layouts = first.layouts.map { it.copy(settings = null) }), "[]", "weekend", live)!!
        assertEquals(live, more.byId(1)!!.settings)
        assertEquals(live, more.byId(3)!!.settings)
        // A copy of the layout on screen takes the live settings, a copy of another layout its own.
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", "[]", mapOf("columns" to 4)), SavedLayout(2, "work", "[]", work)))
        assertEquals(live, LayoutOps.duplicate(set, 1, "[]", live)!!.layouts[1].settings)
        assertEquals(work, LayoutOps.duplicate(set, 2, "[]", live)!!.layouts[2].settings)
        // Renaming and deleting write the live settings back too.
        assertEquals(live, LayoutOps.rename(set, 2, "office", "[]", live)!!.byId(1)!!.settings)
        assertEquals(live, LayoutOps.delete(set, 2, "[]", live)!!.first.byId(1)!!.settings)
    }

    @Test
    fun photosStayHeldWhileALayoutOrTheScreenUsesThem() {
        val set = LayoutSet(
            1,
            listOf(
                // The active entry is stale: the screen has since picked another photo.
                SavedLayout(1, "start", "[]", mapOf("background_photo" to "content://old")),
                SavedLayout(2, "work", "[]", mapOf("background_photo" to "content://work", "start_background" to StartBackground.SOLID.name)),
                SavedLayout(3, "plain", "[]", mapOf("background_photo" to "")),
                SavedLayout(4, "older", "[]"),
            ),
        )
        assertEquals(setOf("content://new", "content://work"), LayoutStartSettings.photosInUse(set, "content://new"))
        assertEquals(setOf("content://work"), LayoutStartSettings.photosInUse(set, null))
        assertEquals(setOf("content://x"), LayoutStartSettings.photosInUse(null, "content://x"))
        // Deleting "work" lets its photo go.
        val (afterDelete, _) = LayoutOps.delete(set, 2, "[]", mapOf("background_photo" to "content://new"))!!
        assertEquals(setOf("content://new"), LayoutStartSettings.photosInUse(afterDelete, "content://new"))
    }

    @Test
    fun aSwitchThatChangesTheGridFadesInsteadOfGliding() {
        assertFalse(LayoutStartSettings.changesGrid(mapOf("columns" to 4), null))
        assertFalse(LayoutStartSettings.changesGrid(mapOf("columns" to 4, "start_top_padding" to 3), mapOf("columns" to 4)))
        assertTrue(LayoutStartSettings.changesGrid(mapOf("columns" to 4), mapOf("columns" to 6)))
        assertTrue(LayoutStartSettings.changesGrid(emptyMap(), mapOf("start_left_padding" to 20)))
        // Without a glide no tile keeps its key, so none moves from where the old grid had it.
        val keys = TileKeys()
        keys.switched(listOf(app(1, "phone")), listOf(app(1, "phone")), glide = false)
        assertFalse(keys.glides)
        assertTrue(keys.keyOf(1) < 0)
        keys.switched(listOf(app(1, "phone")), listOf(app(2, "phone")))
        assertTrue(keys.glides)
    }

    @Test
    fun settingsMenuOrder() {
        assertEquals(
            listOf("start", "app list", "search", "appearance", "animations", "tiles", "metro plus", "gestures", "backup", "about"),
            SettingsPage.menu.map { it.title },
        )
        assertEquals(SettingsPage.START, SettingsPage.LAYOUTS.parent)
        assertTrue(SettingsPage.START.summary.startsWith("layouts"))
        assertEquals("double tap, two-finger swipes, sleep · metro plus", SettingsPage.GESTURES.menuSummary(unlocked = false))
        assertEquals("double tap, two-finger swipes, sleep", SettingsPage.GESTURES.menuSummary(unlocked = true))
        assertEquals(SettingsPage.START.summary, SettingsPage.START.menuSummary(unlocked = false))
    }

    @Test
    fun aLockedSearchForLayoutsPointsAtMetroPlus() {
        listOf("layouts", "Layout work", "switch to layout weekend", "start layouts", "layout").forEach {
            assertTrue(it, LauncherActions.asksForLayouts(it))
        }
        listOf("la", "lay", "maps", "play").forEach { assertFalse(it, LauncherActions.asksForLayouts(it)) }
        val ctx = SearchContext(RuntimeEnvironment.getApplication(), emptyList(), io.github.danielphingston.metro.data.LauncherSettings())
        val rows = runBlocking { LockedLayoutsProvider.results("layout work", ctx) }
        assertEquals(listOf(LockedLayoutsProvider.TITLE), rows.map { it.title })
        assertTrue(runBlocking { LockedLayoutsProvider.results("maps", ctx) }.isEmpty())
    }
}
