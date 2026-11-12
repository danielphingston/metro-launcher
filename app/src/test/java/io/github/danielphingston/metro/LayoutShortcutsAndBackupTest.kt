package io.github.danielphingston.metro

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.BackupException
import io.github.danielphingston.metro.data.Gesture
import io.github.danielphingston.metro.data.GestureAction
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.LayoutShortcuts
import io.github.danielphingston.metro.data.LayoutsJson
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileColourMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Links and shortcuts that switch the start layout, and the newer stored state going through a backup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LayoutShortcutsAndBackupTest {
    private val tiles = """[{"id":1,"size":"MEDIUM","type":"me"},{"id":2,"size":"SMALL","type":"layoutSwitch"},""" +
        """{"id":3,"size":"WIDE","type":"weather","color":"#FF0000"}]"""
    private val set = LayoutSet(
        active = 10,
        layouts = listOf(SavedLayout(10, "start", "[]"), SavedLayout(20, "Work", tiles), SavedLayout(30, "next", "[]")),
    )

    @Test
    fun linkFindsALayoutByNameOrId() {
        assertEquals(LayoutShortcuts.Target.Layout(20), LayoutShortcuts.resolve("work", set))
        assertEquals(LayoutShortcuts.Target.Layout(20), LayoutShortcuts.resolve(" WORK ", set))
        assertEquals(LayoutShortcuts.Target.Layout(20), LayoutShortcuts.resolve("20", set))
        assertEquals("20", LayoutShortcuts.uri(20).lastPathSegment)
        assertNull(LayoutShortcuts.resolve("holiday", set))
        assertNull(LayoutShortcuts.resolve("99", set))
        assertNull(LayoutShortcuts.resolve(null, set))
        assertNull(LayoutShortcuts.resolve("work", null))
    }

    @Test
    fun nextIsTheCommandUnlessALayoutHasThatName() {
        assertEquals(LayoutShortcuts.Target.Layout(30), LayoutShortcuts.resolve("next", set))
        val plain = set.copy(layouts = set.layouts.take(2))
        assertEquals(LayoutShortcuts.Target.Next, LayoutShortcuts.resolve("Next", plain))
    }

    @Test
    fun shortcutsOnlyWhenThereIsAChoice() {
        assertTrue(LayoutShortcuts.shortcutLayouts(null, 4).isEmpty())
        assertTrue(LayoutShortcuts.shortcutLayouts(set.copy(layouts = set.layouts.take(1)), 4).isEmpty())
        assertEquals(listOf(10L, 20L, 30L), LayoutShortcuts.shortcutLayouts(set, 4).map { it.id })
        assertEquals(listOf(10L, 20L), LayoutShortcuts.shortcutLayouts(set, 2).map { it.id })
    }

    @Test
    fun newerStateSurvivesABackup() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("tiles") to tiles,
            stringPreferencesKey(LayoutsJson.KEY) to LayoutsJson.encode(set),
            stringPreferencesKey("tile_colour_mode") to "ICON",
            intPreferencesKey("tile_icon_colour_vividness") to 80,
            stringPreferencesKey("gesture_double_tap") to "sleep",
            booleanPreferencesKey("photo_moves_with_tiles") to true,
        )
        val restored = Backup.decode(Backup.encode(prefs, "x", 0))
        assertEquals(prefs.asMap(), restored.asMap())
        Backup.checkKeyTypes(restored)

        val settings = SettingsRepository(RuntimeEnvironment.getApplication()).settingsOf(restored)
        assertEquals(TileColourMode.ICON, settings.tileColourMode)
        assertEquals(80, settings.tileIconVividness)
        assertEquals(GestureAction.SLEEP, settings.gestures[Gesture.DOUBLE_TAP].action)
        assertTrue(settings.photoMovesWithTiles)

        val layouts = LayoutsJson.decode(restored[stringPreferencesKey(LayoutsJson.KEY)])!!
        assertEquals(listOf("start", "Work", "next"), layouts.layouts.map { it.name })
        val read = TileJson.read(layouts.byId(20)!!.tiles)!!
        assertTrue(read.unread.top.isEmpty())
        assertEquals(TileContent.Me, read.tiles[0].content)
        assertEquals(TileContent.LayoutSwitch(null), read.tiles[1].content)
        assertEquals("#FF0000", read.tiles[2].color)
    }

    @Test
    fun setupFlagStaysOutOfBackups() {
        val prefs = mutablePreferencesOf(booleanPreferencesKey("onboarding_done") to true, stringPreferencesKey("theme") to "LIGHT")
        val text = Backup.encode(prefs, "x", 0)
        assertFalse(text.contains("onboarding_done"))
        assertEquals(setOf("theme"), Backup.decode(text).asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun layoutsOfTheWrongKindAreRejected() {
        val prefs = mutablePreferencesOf(intPreferencesKey(LayoutsJson.KEY) to 3)
        try {
            Backup.checkKeyTypes(prefs)
            throw AssertionError("a number under the layouts key must be refused")
        } catch (_: BackupException) {
        }
    }
}
