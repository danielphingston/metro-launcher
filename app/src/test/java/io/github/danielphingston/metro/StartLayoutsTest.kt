package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.LayoutNames
import io.github.danielphingston.metro.data.LayoutOps
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.LayoutsJson
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.data.WidgetReclaim
import io.github.danielphingston.metro.ui.search.LauncherAction
import io.github.danielphingston.metro.ui.search.LauncherActions
import io.github.danielphingston.metro.ui.start.TileKeys
import io.github.danielphingston.metro.ui.start.TileMatching
import io.github.danielphingston.metro.ui.start.layoutSwitchEntries
import io.github.danielphingston.metro.ui.tiles.LayoutSwitchTiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.danielphingston.metro.ui.tiles.LayoutSwitchTiles.Outcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is an Android API; Robolectric provides the real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartLayoutsTest {
    private fun app(id: Long, pkg: String, x: Int = 0, y: Int = 0, size: TileSize = TileSize.MEDIUM) =
        TileSpec(id, size, TileContent.App(AppKey(pkg, "$pkg.Main", 0)), x, y)

    private fun widget(id: Long, widgetId: Int, x: Int = 0, y: Int = 0) =
        TileSpec(id, TileSize.MEDIUM, TileContent.Widget(widgetId, "com.example/.W", 0L), x, y)

    private fun json(vararg tiles: TileSpec) = TileJson.encode(tiles.toList()).toString()

    /** A tile of a kind this version doesn't know, as a newer build could save it. */
    private val future = """{"id":40,"size":"SMALL","type":"hologram","x":0,"y":6}"""

    @Test
    fun layoutsRoundTripWithEntriesThisVersionCannotRead() {
        val tiles = JSONArray(json(app(1, "a"))).put(JSONObject(future)).toString()
        val set = LayoutSet(2, listOf(SavedLayout(1, "start", json(app(1, "a"))), SavedLayout(2, "work", tiles)))
        val decoded = LayoutsJson.decode(LayoutsJson.encode(set))!!
        assertEquals(set.active, decoded.active)
        assertEquals(set.layouts.map { it.id to it.name }, decoded.layouts.map { it.id to it.name })
        // The tiles come back entry for entry, the unknown one included.
        assertEquals(JSONArray(tiles).toString(), decoded.byId(2)!!.tiles)
        assertEquals(1, TileJson.read(decoded.byId(2)!!.tiles)!!.unread.top.size)
        val root = JSONObject(LayoutsJson.encode(set))
        assertEquals(2L, root.getLong("active"))
        assertEquals("work", root.getJSONArray("layouts").getJSONObject(1).getString("name"))
        assertTrue(root.getJSONArray("layouts").getJSONObject(1).get("tiles") is JSONArray)
    }

    @Test
    fun garbageReadsAsNoLayouts() {
        assertNull(LayoutsJson.decode(null))
        assertNull(LayoutsJson.decode("not json"))
        assertNull(LayoutsJson.decode("[]"))
        assertNull(LayoutsJson.decode("""{"active":1,"layouts":[]}"""))
        assertNull(LayoutsJson.decode("""{"active":1,"layouts":[{"id":1,"name":"x","tiles":"oops"}]}"""))
    }

    @Test
    fun damagedEntriesAreDroppedAndAMissingActiveFallsBackToTheFirst() {
        val set = LayoutsJson.decode(
            """{"active":9,"layouts":[{"name":"no id","tiles":[]},{"id":3,"name":"  work  ","tiles":[]},{"id":3,"name":"again","tiles":[]},{"id":4,"tiles":[]}]}""",
        )!!
        assertEquals(listOf(3L, 4L), set.layouts.map { it.id })
        assertEquals("work", set.byId(3)!!.name)
        assertEquals("layout 4", set.byId(4)!!.name)
        assertEquals(3L, set.active)
    }

    @Test
    fun theFirstSaveKeepsTheCurrentStartAsStart() {
        val live = json(app(1, "a"), widget(2, 70, 2, 0))
        val set = LayoutOps.saveAs(null, live, " weekend ")!!
        assertEquals(1L, set.active)
        assertEquals(listOf("start", "weekend"), set.layouts.map { it.name })
        assertEquals(listOf(live, live), set.layouts.map { it.tiles })
        // "start" is taken by the current start even before the first save.
        assertNull(LayoutOps.saveAs(null, live, "Start"))
        // Later saves keep the active layout and add another copy of the screen.
        val more = LayoutOps.saveAs(set, json(app(1, "a")), "work")!!
        assertEquals(1L, more.active)
        assertEquals(json(app(1, "a")), more.byId(1)!!.tiles)
        assertEquals(3L, more.layouts.last().id)
    }

    @Test
    fun switchingKeepsTheLiveTilesInTheLayoutItLeaves() {
        val a = json(app(1, "a"))
        val b = json(app(1, "b"), app(2, "c", 2, 0))
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", a), SavedLayout(2, "work", b)))
        val editedA = json(app(1, "a"), app(2, "d", 2, 0))
        val toB = LayoutOps.switch(set, editedA, 2)!!
        assertEquals(2L, toB.active)
        assertEquals(editedA, toB.byId(1)!!.tiles)
        assertEquals(b, toB.byId(2)!!.tiles)
        // Back and forth with no edits loses nothing.
        val back = LayoutOps.switch(toB, toB.byId(2)!!.tiles, 1)!!
        val again = LayoutOps.switch(back, back.byId(1)!!.tiles, 2)!!
        assertEquals(toB, again)
        // Switching to the layout on screen, or to one that isn't there, does nothing.
        assertNull(LayoutOps.switch(toB, b, 2))
        assertNull(LayoutOps.switch(toB, b, 99))
    }

    @Test
    fun namesAreTrimmedShortAndUnique() {
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", "[]")))
        assertEquals("a b", LayoutNames.clean("  a   b "))
        assertEquals(LayoutNames.MAX, LayoutNames.clean("x".repeat(40)).length)
        assertEquals("type a name", LayoutNames.problem(LayoutNames.clean("   "), set))
        assertTrue(LayoutNames.problem("WORK", set) != null)
        assertNull(LayoutNames.problem("WORK", set, exceptId = 2))
        assertNull(LayoutOps.rename(set, 1, "Work", "[]"))
        assertEquals("office", LayoutOps.rename(set, 2, " office ", "[]")!!.byId(2)!!.name)
        assertNull(LayoutOps.rename(set, 9, "office", "[]"))
    }

    @Test
    fun duplicateAndDelete() {
        val live = json(app(1, "a"))
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", json(widget(1, 70)))))
        val dup = LayoutOps.duplicate(set, 2, live)!!
        assertEquals(listOf("start", "work", "work copy"), dup.layouts.map { it.name })
        assertEquals(dup.byId(2)!!.tiles, dup.byId(3)!!.tiles)
        assertEquals(live, dup.byId(1)!!.tiles)
        assertEquals("work copy 2", LayoutOps.duplicate(dup, 2, live)!!.layouts[2].name)
        // The active layout is copied from the screen.
        assertEquals(live, LayoutOps.duplicate(set, 1, live)!!.layouts[1].tiles)
        assertNull(LayoutOps.delete(dup, 1, live))
        val (afterDelete, gone) = LayoutOps.delete(dup, 2, live)!!
        assertEquals(listOf(1L, 3L), afterDelete.layouts.map { it.id })
        assertEquals("work", gone.name)
    }

    @Test
    fun widgetIdsOfEveryStoredLayoutAreReferenced() {
        val set = LayoutSet(
            1,
            listOf(
                // The active entry is stale: its widget 60 has since been unpinned from the screen.
                SavedLayout(1, "start", json(widget(1, 60))),
                SavedLayout(2, "work", json(widget(1, 70), TileSpec(5, TileSize.MEDIUM, TileContent.Folder("f", listOf(widget(6, 71)))))),
                SavedLayout(3, "copy", json(widget(1, 70))),
            ),
        )
        val live = listOf(widget(1, 80))
        val used = WidgetReclaim.referencedIds(live) + set.inactiveTiles().flatMap(WidgetReclaim::referencedIds)
        assertEquals(setOf(70, 71, 80), used)
        assertEquals(listOf(60, 99), WidgetReclaim.toDelete(listOf(60, 70, 71, 80, 99), used, undecoded = false))
        assertFalse(set.hasUndecoded())
        val newer = set.copy(layouts = set.layouts + SavedLayout(4, "newer", JSONArray().put(JSONObject(future)).toString()))
        assertTrue(newer.hasUndecoded())
    }

    @Test
    fun deletingALayoutFreesOnlyWidgetsNothingElseUses() {
        var set = LayoutSet(
            1,
            listOf(
                SavedLayout(1, "start", "[]"),
                SavedLayout(2, "work", json(widget(1, 70), widget(2, 71, 2, 0))),
                SavedLayout(3, "work copy", json(widget(1, 70))),
            ),
        )
        var live: List<TileSpec> = listOf(widget(1, 72))
        val released = mutableListOf<Int>()
        val undo = TileUndo(
            current = { live },
            restore = { live = it },
            alsoHeld = { set.inactiveTiles() },
            release = { gone -> released += gone.map { (it.content as TileContent.Widget).appWidgetId } },
        )
        val (next, gone) = LayoutOps.delete(set, 2, TileJson.encode(live).toString())!!
        set = next
        undo.releaseUnreferenced(TileJson.read(gone.tiles)!!.tiles)
        assertEquals(listOf(71), released)
        // Unpinning the live widget while an undo step holds it, then forgetting the steps.
        undo.record("unpin") { live = emptyList() }
        undo.releaseUnreferenced(listOf(widget(1, 72)))
        assertEquals(listOf(71), released)
        undo.clear()
        assertEquals(listOf(71, 72), released)
    }

    @Test
    fun layoutSwitchTilesRoundTrip() {
        val tiles = listOf(
            TileSpec(1, TileSize.SMALL, TileContent.LayoutSwitch(3), 0, 0),
            TileSpec(2, TileSize.WIDE, TileContent.LayoutSwitch(null), 0, 1),
        )
        val encoded = TileJson.encode(tiles)
        assertEquals("layoutSwitch", encoded.getJSONObject(0).getString("type"))
        assertEquals(3L, encoded.getJSONObject(0).getLong("layout"))
        assertFalse(encoded.getJSONObject(1).has("layout"))
        assertEquals(tiles, TileJson.decode(JSONArray(encoded.toString())))
    }

    @Test
    fun switchTilesNameTheirLayoutOrTheNextOne() {
        val set = LayoutSet(2, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", "[]"), SavedLayout(3, "weekend", "[]")))
        assertEquals(LayoutSwitchTiles.Face("weekend", null), LayoutSwitchTiles.face(TileContent.LayoutSwitch(3), set))
        assertEquals(LayoutSwitchTiles.Face("next layout", "weekend"), LayoutSwitchTiles.face(TileContent.LayoutSwitch(null), set))
        assertEquals(LayoutSwitchTiles.Face("layout removed", null), LayoutSwitchTiles.face(TileContent.LayoutSwitch(9), set))
        assertEquals("next layout, weekend", LayoutSwitchTiles.label(TileContent.LayoutSwitch(null), set))
        // The last layout wraps round to the first; with one layout there is nowhere to go.
        assertEquals(1L, set.copy(active = 3).next()!!.id)
        assertNull(LayoutSet(1, listOf(SavedLayout(1, "start", "[]"))).next())
        assertEquals(listOf("next layout", "start", "work", "weekend"), layoutSwitchEntries(set).map { it.first })
    }

    @Test
    fun tilesMatchByWhatTheyAreThenById() {
        val from = listOf(
            app(1, "phone"), widget(2, 70), TileSpec(3, TileSize.WIDE, TileContent.Weather),
            TileSpec(4, TileSize.MEDIUM, TileContent.Folder("f", emptyList())), app(5, "mail"),
            TileSpec(6, TileSize.SMALL, TileContent.QuickSetting(QuickSettingKind.FLASHLIGHT)),
        )
        val to = listOf(
            app(9, "phone"), widget(1, 70), TileSpec(2, TileSize.SMALL, TileContent.Weather),
            TileSpec(4, TileSize.MEDIUM, TileContent.Folder("g", emptyList())), app(5, "maps"), widget(6, 99),
        )
        // Mail and maps share an id but are different apps of the same kind; the new widget shares id 6 with a quick setting.
        assertEquals(mapOf(9L to 1L, 1L to 2L, 2L to 3L, 4L to 4L, 5L to 5L), TileMatching.match(from, to))
    }

    @Test
    fun tilesKeepTheirKeysAcrossASwitch() {
        val keys = TileKeys()
        val from = listOf(app(1, "phone"), app(2, "mail"), app(3, "camera"))
        assertEquals(2L, keys.keyOf(2))
        // Phone and camera stay (under new ids); mail leaves; a new tile takes mail's old id.
        val to = listOf(app(7, "phone"), app(2, "maps").copy(content = TileContent.Weather), app(8, "camera"))
        keys.switched(from, to)
        assertEquals(1, keys.generation)
        assertEquals(1L, keys.keyOf(7))
        assertEquals(3L, keys.keyOf(8))
        // Id 2 was on screen as mail: the weather tile gets a key no tile had.
        assertTrue(keys.keyOf(2) < 0)
        // A tile pinned later whose id another tile kept as its key gets its own, for good.
        val fresh = keys.keyOf(1)
        assertTrue(fresh < 0 && fresh != keys.keyOf(2))
        assertEquals(fresh, keys.keyOf(1))
        assertEquals(42L, keys.keyOf(42))
        // A second switch carries the keys on.
        keys.switched(to, listOf(app(1, "camera")))
        assertEquals(3L, keys.keyOf(1))
        assertNotEquals(keys.keyOf(1), keys.keyOf(3))
    }

    @Test
    fun searchFindsLayoutsByName() {
        assertEquals(
            listOf(LauncherAction.SwitchLayout("work"), LauncherAction.Command(LauncherActions.Cmd.LAYOUTS)),
            LauncherActions.parse("layout  work"),
        )
        assertEquals(LauncherAction.Command(LauncherActions.Cmd.LAYOUTS), LauncherActions.parse("layouts").first())
        val layouts = listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", "[]"), SavedLayout(3, "weekend", "[]"))
        assertEquals(listOf(3L), LauncherActions.layoutsFor("week", layouts).map { it.id })
    }

    @Test
    fun newEmptyLayoutHasNoTilesKeepsTheCurrentSettingsAndStaysOff() {
        val live = json(app(1, "a"), widget(2, 70, 2, 0))
        val settings = mapOf<String, Any>("start_columns" to 6)
        // First time: the current start becomes "start", the empty layout is added after it.
        val first = LayoutOps.newEmpty(null, live, " quiet ", settings)!!
        assertEquals(1L, first.active)
        assertEquals(listOf("start", "quiet"), first.layouts.map { it.name })
        assertEquals(live, first.byId(1)!!.tiles)
        assertEquals("[]", first.byId(2)!!.tiles)
        assertTrue(first.byId(2)!!.hasNoTiles)
        assertFalse(first.byId(1)!!.hasNoTiles)
        assertEquals(settings, first.byId(2)!!.settings)
        // Nothing of the empty layout holds a widget; the live one is not an inactive layout.
        assertTrue(first.inactiveTiles().flatMap(WidgetReclaim::referencedIds).isEmpty())
        assertNull(LayoutOps.newEmpty(null, live, "Start"))
        assertNull(LayoutOps.newEmpty(first, live, "QUIET"))
        // Later: the active layout is written back and keeps its tiles, ids don't clash.
        val edited = json(app(1, "a"))
        val more = LayoutOps.newEmpty(first, edited, "empty two", mapOf("start_columns" to 4))!!
        assertEquals(1L, more.active)
        assertEquals(edited, more.byId(1)!!.tiles)
        assertEquals(3L, more.layouts.last().id)
        assertEquals(mapOf<String, Any>("start_columns" to 4), more.layouts.last().settings)
        assertTrue(LayoutsJson.decode(LayoutsJson.encode(more))!!.layouts.last().hasNoTiles)
        // Switching to it leaves the live tiles in the layout it leaves.
        val toEmpty = LayoutOps.switch(more, edited, 3)!!
        assertEquals("[]", toEmpty.activeLayout.tiles)
    }

    @Test
    fun aLayoutSearchRowRunsInPlaceAndPinsTheRealSwitchTile() {
        val row = LauncherActions.layoutItem(SavedLayout(2, "work", "[]"), activeId = 1)
        // Nothing is launched (no turnstile): the row runs in place.
        assertTrue(row.action is io.github.danielphingston.metro.ui.search.ItemAction.Run)
        // It pins the same tile the add menu's "layouts" entry adds, not an action tile.
        assertEquals(TileContent.LayoutSwitch(2), row.pin)
        assertEquals(layoutSwitchEntries(LayoutSet(1, listOf(SavedLayout(2, "work", "[]")))).last().second, row.pin)
        assertEquals("switch the start screen to it", row.subtitle)
        assertEquals("on start now", LauncherActions.layoutItem(SavedLayout(1, "start", "[]"), 1).subtitle)
        // Running it asks the launcher to switch, the same way a switch tile does.
        val asked = mutableListOf<Long>()
        val commands = object : io.github.danielphingston.metro.ui.search.LauncherCommands {
            override fun editStart() = Unit
            override fun appInfo(app: io.github.danielphingston.metro.data.AppInfo) = Unit
            override fun uninstall(app: io.github.danielphingston.metro.data.AppInfo) = Unit
            override fun recordAssistantQuestion(question: String) = Unit
            override fun switchLayout(id: Long) { asked += id }
        }
        (row.action as io.github.danielphingston.metro.ui.search.ItemAction.Run)
            .run(io.github.danielphingston.metro.ui.search.ActionEnv(org.robolectric.RuntimeEnvironment.getApplication(), null, null, commands))
        assertEquals(listOf(2L), asked)
    }

    @Test
    fun searchAndTilesSwitchLayoutsTheSameWay() {
        val tiles = LayoutSwitchTiles
        val set = LayoutSet(1, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", "[]")))
        // Locked: "unlock to use" and the Metro Plus page, whatever the layout.
        assertEquals(Outcome.Locked, tiles.outcome(TileContent.LayoutSwitch(2), unlocked = false, set))
        // Gone: the layouts page.
        assertEquals(Outcome.Missing, tiles.outcome(TileContent.LayoutSwitch(9), unlocked = true, set))
        assertEquals(Outcome.Missing, tiles.outcome(TileContent.LayoutSwitch(2), unlocked = true, null))
        // Already on start: nothing (search just closes).
        assertEquals(Outcome.AlreadyActive, tiles.outcome(TileContent.LayoutSwitch(1), unlocked = true, set))
        assertEquals(Outcome.Switch(2), tiles.outcome(TileContent.LayoutSwitch(2), unlocked = true, set))
        // "Next layout" goes round.
        assertEquals(Outcome.Switch(2), tiles.outcome(TileContent.LayoutSwitch(null), unlocked = true, set))
        assertEquals(Outcome.Missing, tiles.outcome(TileContent.LayoutSwitch(null), unlocked = true, LayoutSet(1, listOf(SavedLayout(1, "start", "[]")))))
    }
}
