package io.github.danielphingston.metro

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.LaunchTarget
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.LayoutsJson
import io.github.danielphingston.metro.data.PhotoUse
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.StartEdit
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileLook
import io.github.danielphingston.metro.data.TileLooks
import io.github.danielphingston.metro.data.TileOption
import io.github.danielphingston.metro.data.TileOptions
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStyle
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.ui.start.tileDecor
import io.github.danielphingston.metro.ui.start.tileInk
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.TileDecor
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The customise sheet's model: the old tile styles and the new toggles map onto each other both
 * ways, the tile JSON keeps old layouts and backups exactly as they were, and every tile kind
 * offers exactly what applies to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TileOptionsTest {
    private val appKey = AppKey("com.example", "com.example.Main", 0)
    private fun app(id: Long = 1, style: TileStyle = TileStyle.ICON, image: String? = null) =
        TileSpec(id, TileSize.MEDIUM, TileContent.App(appKey), 0, 0, style = style, image = image)
    private fun folder(id: Long = 9, style: TileStyle = TileStyle.ICON, image: String? = null) =
        TileSpec(id, TileSize.MEDIUM, TileContent.Folder("games", listOf(app(10), app(11))), 0, 0, style = style, image = image)

    private fun roundTrip(tiles: List<TileSpec>) = TileJson.decode(JSONArray(TileJson.encode(tiles).toString()))

    // ---- old style <-> look ----

    @Test
    fun everyOldStyleMeansExactlyItsLook() {
        val expected = mapOf(
            TileStyle.ICON to TileLook(showName = true, showIcon = true, photo = PhotoUse.NONE),
            TileStyle.ICON_ONLY to TileLook(showName = false, showIcon = true, photo = PhotoUse.NONE),
            TileStyle.NAME_ONLY to TileLook(showName = true, showIcon = false, photo = PhotoUse.NONE),
            TileStyle.CUSTOM_ICON to TileLook(showName = true, showIcon = true, photo = PhotoUse.ICON),
            TileStyle.FULL_IMAGE to TileLook(showName = true, showIcon = false, photo = PhotoUse.FILL),
            TileStyle.IMAGE_ICON to TileLook(showName = true, showIcon = true, photo = PhotoUse.FILL),
        )
        assertEquals(TileStyle.entries.toSet(), expected.keys)
        for ((style, look) in expected) {
            assertEquals(style.name, look, TileLooks.base(style))
            assertEquals(style.name, look, TileLooks.of(style, hideName = false, hideIcon = false))
            // And back: the look an old style expresses is stored as that style alone, no new fields.
            assertEquals(style.name, TileLooks.Stored(style), TileLooks.stored(look))
            assertEquals(style.name, look, app(style = style, image = "content://p").look)
        }
    }

    @Test
    fun everyOldFolderStyleMeansExactlyItsLook() {
        val expected = mapOf(
            TileStyle.ICON to TileLook(showName = true, showIcon = true),
            TileStyle.ICON_ONLY to TileLook(showName = false, showIcon = true),
            TileStyle.IMAGE_ICON to TileLook(showName = true, showIcon = true, photo = PhotoUse.FILL),
            // A folder's "full tile image" is the photo alone.
            TileStyle.FULL_IMAGE to TileLook(showName = false, showIcon = false, photo = PhotoUse.FILL),
        )
        for ((style, look) in expected) {
            assertEquals(style.name, look, TileLooks.base(style, folder = true))
            assertEquals(style.name, TileLooks.Stored(style), TileLooks.stored(look, folder = true))
        }
    }

    @Test
    fun newCombinationsUseTheNearestStyleAndFlags() {
        val cases = listOf(
            TileLook(showName = false, showIcon = true, photo = PhotoUse.ICON) to TileLooks.Stored(TileStyle.CUSTOM_ICON, hideName = true),
            TileLook(showName = true, showIcon = false, photo = PhotoUse.ICON) to TileLooks.Stored(TileStyle.CUSTOM_ICON, hideIcon = true),
            TileLook(showName = false, showIcon = true, photo = PhotoUse.FILL) to TileLooks.Stored(TileStyle.IMAGE_ICON, hideName = true),
            TileLook(showName = false, showIcon = false, photo = PhotoUse.FILL) to TileLooks.Stored(TileStyle.FULL_IMAGE, hideName = true),
        )
        for ((look, stored) in cases) {
            assertEquals(look.toString(), stored, TileLooks.stored(look))
            assertEquals(look.toString(), look, TileLooks.of(stored.style, stored.hideName, stored.hideIcon))
        }
        val folderCases = listOf(
            TileLook(showName = true, showIcon = false) to TileLooks.Stored(TileStyle.ICON, hideIcon = true),
            TileLook(showName = false, showIcon = true, photo = PhotoUse.FILL) to TileLooks.Stored(TileStyle.IMAGE_ICON, hideName = true),
            TileLook(showName = true, showIcon = false, photo = PhotoUse.FILL) to TileLooks.Stored(TileStyle.IMAGE_ICON, hideIcon = true),
        )
        for ((look, stored) in folderCases) {
            assertEquals(look.toString(), stored, TileLooks.stored(look, folder = true))
            assertEquals(look.toString(), look, TileLooks.of(stored.style, stored.hideName, stored.hideIcon, folder = true))
        }
    }

    @Test
    fun everyLookSurvivesBeingStored() {
        for (folder in listOf(false, true)) for (name in listOf(false, true)) for (icon in listOf(false, true)) for (photo in PhotoUse.entries) {
            val look = TileLook(name, icon, photo)
            val stored = TileLooks.stored(look, folder)
            val back = TileLooks.of(stored.style, stored.hideName, stored.hideIcon, folder)
            assertEquals("$look folder=$folder", TileLooks.normalized(look, folder), back)
            assertTrue(back.isValid)
        }
    }

    @Test
    fun aBlankLookGetsItsIconBack() {
        assertEquals(TileLook(false, true, PhotoUse.NONE), TileLooks.normalized(TileLook(false, false, PhotoUse.NONE)))
        assertEquals(TileLook(false, true, PhotoUse.ICON), TileLooks.normalized(TileLook(false, false, PhotoUse.ICON)))
        // A folder has no photo icon.
        assertEquals(TileLook(true, true, PhotoUse.NONE), TileLooks.normalized(TileLook(true, true, PhotoUse.ICON), folder = true))
        // Flags that would blank a tile are ignored.
        assertEquals(TileLooks.base(TileStyle.ICON_ONLY), TileLooks.of(TileStyle.ICON_ONLY, hideName = false, hideIcon = true))
        assertEquals(TileLooks.base(TileStyle.NAME_ONLY), TileLooks.of(TileStyle.NAME_ONLY, hideName = true, hideIcon = false))
    }

    @Test
    fun aMissingPhotoDrawsAsBefore() {
        // As StyledTileFace always did: a photo style without its photo is the plain icon tile.
        for (style in TileStyle.entries.filter { it.needsImage }) {
            assertEquals(style.name, TileLook(), TileLooks.shown(app(style = style)))
        }
        // A folder's "full tile image" without its photo keeps its grid and has no name.
        assertEquals(TileLook(showName = false, showIcon = true), TileLooks.shown(folder(style = TileStyle.FULL_IMAGE)))
        assertEquals(TileLook(), TileLooks.shown(folder(style = TileStyle.IMAGE_ICON)))
        assertEquals(TileLook(showName = false, showIcon = false, photo = PhotoUse.FILL), TileLooks.shown(folder(style = TileStyle.FULL_IMAGE, image = "content://p")))
    }

    // ---- the at-least-one rule and the edits ----

    @Test
    fun theLastOfNameAndIconCannotBeTurnedOff() {
        assertTrue(TileOptions.canHide(app(), name = true))
        assertTrue(TileOptions.canHide(app(), name = false))
        assertFalse(TileOptions.canHide(app(style = TileStyle.ICON_ONLY), name = false))
        assertFalse(TileOptions.canHide(app(style = TileStyle.NAME_ONLY), name = true))
        // A photo filling the tile is enough to show: both may go.
        assertTrue(TileOptions.canHide(app(style = TileStyle.FULL_IMAGE, image = "content://p"), name = true))
        // An edit that would blank the tile keeps the icon.
        val blank = TileOptions.withIconShown(app(style = TileStyle.ICON_ONLY), false)
        assertEquals(TileStyle.ICON_ONLY, blank.style)
        assertTrue(blank.look.showIcon)
    }

    @Test
    fun editsKeepStyleTheClosestOldValue() {
        val hidden = TileOptions.withNameShown(app(), false, hasLook = true)
        assertEquals(TileStyle.ICON_ONLY, hidden.style)
        assertFalse(hidden.hideName)
        val photo = TileOptions.withPhoto(app(), PhotoUse.FILL, "content://p")
        assertEquals(TileStyle.IMAGE_ICON, photo.style)
        assertEquals("content://p", photo.image)
        val full = TileOptions.withIconShown(photo, false)
        assertEquals(TileStyle.FULL_IMAGE, full.style)
        val pure = TileOptions.withNameShown(full, false, hasLook = true)
        assertEquals(TileStyle.FULL_IMAGE, pure.style)
        assertTrue(pure.hideName)
        val none = TileOptions.withPhoto(pure, PhotoUse.NONE, null)
        // No photo, no name: the icon comes back.
        assertEquals(TileStyle.ICON_ONLY, none.style)
        assertNull(none.image)
        // A live face only changes its flag, never the style older versions read.
        val clock = TileOptions.withNameShown(app(), false, hasLook = false)
        assertEquals(TileStyle.ICON, clock.style)
        assertTrue(clock.hideName)
        // A picked pack icon replaces a photo used as the icon.
        val photoIcon = TileOptions.withPhoto(app(), PhotoUse.ICON, "content://p")
        assertEquals(TileStyle.CUSTOM_ICON, photoIcon.style)
        val packed = TileOptions.withIcon(photoIcon, "pack:a:b")
        assertEquals(TileStyle.ICON, packed.style)
        assertNull(packed.image)
        assertEquals("pack:a:b", packed.icon)
    }

    @Test
    fun editsReachFolderChildren() {
        val tiles = listOf(folder())
        val changed = TileOptions.update(tiles, 11) { TileOptions.withTransparency(it, 30) }
        val child = (changed.single().content as TileContent.Folder).children.single { it.id == 11L }
        assertEquals(30, child.transparency)
        assertEquals(tiles, TileOptions.update(tiles, 99) { it.copy(hideName = true) })
    }

    @Test
    fun aWidgetsBackgroundIsItsTransparentFlag() {
        val widget = TileSpec(3, TileSize.WIDE, TileContent.Widget(42, "com.example/.W", 0), 0, 0)
        val off = TileOptions.withBackground(widget, false)
        assertEquals(true, (off.content as TileContent.Widget).transparent)
        assertTrue(off.background)
        assertFalse(off.showsBackground)
        assertEquals(widget, TileOptions.withBackground(off, true))
        val plain = TileOptions.withBackground(app(), false)
        assertFalse(plain.background)
        assertFalse(plain.showsBackground)
        assertEquals(100, TileOptions.withTransparency(app(), 140).transparency)
    }

    // ---- JSON ----

    @Test
    fun oldJsonReadsAsBeforeAndIsWrittenBackUnchanged() {
        val old = """[{"id":1,"size":"MEDIUM","x":0,"y":0,"type":"app","app":"a.b/a.b.Main#0","style":"image","image":"content://p"},""" +
            """{"id":2,"size":"WIDE","x":0,"y":2,"type":"widget","widgetId":7,"provider":"a/.W","serial":0,"transparent":true},""" +
            """{"id":3,"size":"SMALL","x":2,"y":0,"style":"iconOnly","type":"app","app":"a.c/a.c.Main#0"},""" +
            """{"id":4,"size":"WIDE","x":0,"y":4,"type":"weather"}]"""
        val tiles = TileJson.decode(JSONArray(old))
        for (tile in tiles) {
            assertFalse(tile.hideName)
            assertFalse(tile.hideIcon)
            assertTrue(tile.background)
            assertNull(tile.transparency)
        }
        assertEquals(TileStyle.FULL_IMAGE, tiles[0].style)
        assertFalse(tiles[1].showsBackground)
        // Field for field the same JSON: nothing new is written for an old layout.
        assertJsonEquals(JSONArray(old), TileJson.encode(tiles))
    }

    @Test
    fun newFieldsRoundTripAndAreOmittedWhenDefault() {
        val tiles = listOf(
            app(1, TileStyle.IMAGE_ICON, "content://p").copy(hideName = true, transparency = 40),
            app(2, TileStyle.CUSTOM_ICON, "content://q").copy(hideIcon = true, background = false),
            TileSpec(3, TileSize.WIDE, TileContent.Weather, 0, 2, hideName = true, background = false, transparency = 0),
            TileSpec(4, TileSize.WIDE, TileContent.Widget(5, "a/.W", 0, transparent = true), 0, 4),
            folder(5).copy(hideIcon = true),
        )
        assertEquals(tiles, roundTrip(tiles))
        val json = TileJson.encode(tiles)
        assertEquals(true, json.getJSONObject(0).get("hideName"))
        assertEquals(40, json.getJSONObject(0).get("transparency"))
        assertFalse(json.getJSONObject(0).has("background"))
        assertEquals(false, json.getJSONObject(1).get("background"))
        assertEquals(true, json.getJSONObject(1).get("hideIcon"))
        // A widget keeps writing only its own flag.
        assertEquals(true, json.getJSONObject(3).get("transparent"))
        assertFalse(json.getJSONObject(3).has("background"))
        for (key in listOf("hideName", "hideIcon", "background", "transparency")) {
            assertFalse(key, TileJson.encode(listOf(app())).getJSONObject(0).has(key))
        }
    }

    @Test
    fun garbledFieldsDecodeToTheDefault() {
        fun read(extra: String) = TileJson.decode(
            JSONArray("""[{"id":1,"size":"MEDIUM","type":"app","app":"a.b/a.b.Main#0"$extra}]"""),
        ).single()
        assertFalse(read(""","hideName":"yes"""").hideName)
        assertFalse(read(""","hideName":1""").hideName)
        assertFalse(read(""","hideIcon":null""").hideIcon)
        assertTrue(read(""","background":"no"""").background)
        assertTrue(read(""","background":0""").background)
        assertNull(read(""","transparency":"50"""").transparency)
        assertNull(read(""","transparency":150""").transparency)
        assertNull(read(""","transparency":-1""").transparency)
        assertNull(read(""","transparency":12.5""").transparency)
        assertEquals(50, read(""","transparency":50.0""").transparency)
        // Both hidden would blank the tile: both flags are dropped.
        val both = read(""","hideName":true,"hideIcon":true""")
        assertFalse(both.hideName)
        assertFalse(both.hideIcon)
        // A widget saved as "background": false (by any build) reads as transparent.
        val widget = TileJson.decode(
            JSONArray("""[{"id":2,"size":"WIDE","type":"widget","widgetId":7,"provider":"a/.W","serial":0,"background":false}]"""),
        ).single()
        assertEquals(true, (widget.content as TileContent.Widget).transparent)
    }

    @Test
    fun theShippedBackupStillReadsAsPlainTiles() {
        val fixture = File("src/test/resources/backup/format-1.json").readText()
        val prefs = Backup.decode(fixture)
        val raw = checkNotNull(prefs[stringPreferencesKey("tiles")])
        val tiles = TileJson.read(raw)!!.tiles
        val all = tiles + tiles.flatMap { (it.content as? TileContent.Folder)?.children.orEmpty() }
        assertEquals(8, all.size)
        for (tile in all) {
            assertFalse(tile.hideName)
            assertFalse(tile.hideIcon)
            assertTrue(tile.showsBackground)
            assertNull(tile.transparency)
            assertEquals(TileDecor.Default, tileDecor(tile))
        }
        assertEquals(TileLook(showName = false, showIcon = true), tiles.single { it.id == 3L }.look)
        // Written back, the layout is the same JSON it was.
        assertJsonEquals(JSONArray(raw), TileJson.encode(tiles))
    }

    @Test
    fun newFieldsSurviveABackupInTheTilesAndInSavedLayouts() {
        val tiles = listOf(
            app(1, TileStyle.IMAGE_ICON, "content://p").copy(hideName = true, transparency = 25),
            TileSpec(2, TileSize.WIDE, TileContent.Me, 0, 2, hideName = true, background = false),
        )
        val encoded = TileJson.encode(tiles).toString()
        val set = LayoutSet(10, listOf(SavedLayout(10, "start", encoded), SavedLayout(20, "work", encoded)))
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("tiles") to encoded,
            stringPreferencesKey(LayoutsJson.KEY) to LayoutsJson.encode(set),
        )
        val restored = Backup.decode(Backup.encode(prefs, "x", 0))
        assertEquals(prefs.asMap(), restored.asMap())
        assertEquals(tiles, TileJson.read(restored[stringPreferencesKey("tiles")]!!)!!.tiles)
        val layouts = LayoutsJson.decode(restored[stringPreferencesKey(LayoutsJson.KEY)])!!
        assertEquals(tiles, TileJson.read(layouts.byId(20)!!.tiles)!!.tiles)
    }

    // ---- availability per tile kind ----

    private val look = setOf(TileOption.SHOW_NAME, TileOption.SHOW_ICON, TileOption.ICON_SOURCE, TileOption.PHOTO)
    private val surface = setOf(TileOption.BACKGROUND, TileOption.COLOUR, TileOption.TRANSPARENCY)
    private val nameAndSurface = setOf(TileOption.SHOW_NAME) + surface

    private fun options(content: TileContent, inFolder: Boolean = false, ownFace: Boolean = false, widgetSettings: Boolean = false) =
        TileOptions.available(TileSpec(1, TileSize.MEDIUM, content), inFolder, ownFace, widgetSettings)

    @Test
    fun everyTileKindOffersWhatAppliesToIt() {
        val group = setOf(TileOption.GROUP)
        val expected: Map<TileContent, Set<TileOption>> = mapOf(
            TileContent.App(appKey) to look + surface + group,
            TileContent.Shortcut("com.example", "s", 0, "chat") to look + surface + group,
            TileContent.Action("cats", ActionIcon.Glyph("web"), LaunchTarget.Web("https://example.com")) to look + surface,
            TileContent.Folder("games", listOf(app(2))) to setOf(TileOption.SHOW_NAME, TileOption.SHOW_ICON, TileOption.PHOTO) + surface + group,
            TileContent.Widget(1, "a/.W", 0) to surface,
            TileContent.Weather to nameAndSurface,
            TileContent.Photos to nameAndSurface,
            TileContent.People to nameAndSurface,
            TileContent.QuickSetting(QuickSettingKind.FLASHLIGHT) to nameAndSurface,
            TileContent.Assistant to nameAndSurface,
            TileContent.Me to nameAndSurface,
            TileContent.LayoutSwitch(null) to nameAndSurface,
        )
        // All twelve kinds of tile.
        assertEquals(12, expected.keys.map { it::class }.toSet().size)
        for ((content, set) in expected) assertEquals(content.toString(), set, options(content))
    }

    @Test
    fun everyQuickSettingOffersItsNameAndSurface() {
        assertEquals(6, QuickSettingKind.entries.size)
        for (kind in QuickSettingKind.entries) assertEquals(kind.name, nameAndSurface, options(TileContent.QuickSetting(kind)))
    }

    @Test
    fun specialCases() {
        // A folder child behaves like an app tile, but can't start a group.
        assertEquals(look + surface, options(TileContent.App(appKey), inFolder = true))
        assertEquals(look + surface, options(TileContent.Shortcut("com.example", "s", 0, "chat"), inFolder = true))
        // The clock and calendar apps draw their own face: only their label and surface change.
        assertEquals(nameAndSurface + TileOption.GROUP, options(TileContent.App(appKey), ownFace = true))
        // A widget with a settings activity offers it.
        assertEquals(surface + TileOption.WIDGET_SETTINGS, options(TileContent.Widget(1, "a/.W", 0), widgetSettings = true))
        // Widget settings is only ever for widgets.
        assertFalse(TileOption.WIDGET_SETTINGS in options(TileContent.App(appKey), widgetSettings = true))
    }

    // ---- surface: transparency, background, ink ----

    @Test
    fun aTilesOwnTransparencyFollowsTheSameRuleAsTheGlobalOne() {
        val solid = StartBackdrop(StartBackground.SOLID, null, 0.5f) { 0f }
        val photo = StartBackdrop(StartBackground.PHOTO_TILES, null, 0.5f) { 0f }
        assertEquals(solid.tileAlpha, solid.tileAlphaFor(null), 0f)
        assertEquals(photo.tileAlpha, photo.tileAlphaFor(null), 0f)
        assertEquals(0.7f, solid.tileAlpha, 1e-6f)
        assertEquals(0.5f, photo.tileAlpha, 1e-6f)
        // SOLID keeps 40% of the colour even at full transparency, as the global setting does.
        assertEquals(0.4f, solid.tileAlphaFor(100), 1e-6f)
        assertEquals(1f, solid.tileAlphaFor(0), 1e-6f)
        assertEquals(0f, photo.tileAlphaFor(100), 1e-6f)
        assertEquals(0.75f, photo.tileAlphaFor(25), 1e-6f)
        // The surface alpha: none without a background, else the tile's or the start screen's.
        assertEquals(0f, photo.surfaceAlpha(TileDecor(background = false, transparency = 0)), 0f)
        assertEquals(photo.tileAlpha, photo.surfaceAlpha(TileDecor.Default), 0f)
        assertEquals(1f, photo.surfaceAlpha(TileDecor(transparency = 0)), 0f)
    }

    @Test
    fun theInkFollowsTheTilesOwnAlpha() {
        val yellow = Color(0xFFE3C800)
        val photo = StartBackdrop(StartBackground.PHOTO_TILES, null, 0.5f) { 0f }
        // Translucent by the start setting: white ink; the tile made opaque: black ink on yellow.
        assertNull(tileInk(yellow, photo.surfaceAlpha(TileDecor.Default)))
        assertEquals(Color.Black, tileInk(yellow, photo.surfaceAlpha(TileDecor(transparency = 0))))
        // Without a background there is nothing light behind the text.
        assertNull(tileInk(yellow, StartBackdrop.Solid.surfaceAlpha(TileDecor(background = false))))
    }

    @Test
    fun theDecorOfATileComesFromItsSpec() {
        assertEquals(TileDecor.Default, tileDecor(app()))
        assertEquals(TileDecor(showName = false, background = false, transparency = 60), tileDecor(app().copy(hideName = true, background = false, transparency = 60)))
        val clear = TileSpec(3, TileSize.WIDE, TileContent.Widget(42, "a/.W", 0, transparent = true))
        assertEquals(TileDecor(background = false), tileDecor(clear))
    }

    // ---- undo ----

    @Test
    fun eachNewEditIsOneUndoStep() {
        var tiles: List<TileSpec>? = listOf(app(1), TileSpec(2, TileSize.WIDE, TileContent.Weather, 2, 0))
        val undo = TileUndo(current = { tiles }, restore = { tiles = it }, alsoHeld = { emptyList() }, release = {})
        val start = tiles
        fun edit(kind: StartEdit, id: Long, change: (TileSpec) -> TileSpec) =
            undo.record(kind.label()) { tiles = TileOptions.update(tiles!!, id, change) }
        assertTrue(edit(StartEdit.STYLE, 1) { TileOptions.withNameShown(it, false, hasLook = true) })
        assertEquals("tile style changed", undo.next.value)
        val styled = tiles
        assertTrue(edit(StartEdit.BACKGROUND, 2) { TileOptions.withBackground(it, false) })
        assertEquals("tile background changed", undo.next.value)
        val noBackground = tiles
        assertTrue(edit(StartEdit.TRANSPARENCY, 2) { TileOptions.withTransparency(it, 70) })
        assertEquals("tile transparency changed", undo.next.value)
        val transparent = tiles
        assertTrue(edit(StartEdit.COLOUR, 1) { it.copy(color = "#FF0000") })
        assertTrue(edit(StartEdit.ICON, 1) { TileOptions.withIcon(it, "glyph:web") })
        assertTrue(edit(StartEdit.STYLE, 2) { TileOptions.withNameShown(it, false, hasLook = false) })
        // Setting what is already set is no step.
        assertFalse(edit(StartEdit.TRANSPARENCY, 2) { TileOptions.withTransparency(it, 70) })

        undo.undo() // name of the weather tile
        undo.undo() // icon
        undo.undo() // colour
        assertEquals(transparent, tiles)
        undo.undo()
        assertEquals(noBackground, tiles)
        undo.undo()
        assertEquals(styled, tiles)
        undo.undo()
        assertEquals(start, tiles)
    }

    private fun assertJsonEquals(expected: JSONArray, actual: JSONArray) {
        assertEquals(expected.length(), actual.length())
        for (i in 0 until expected.length()) assertJsonEquals(expected.getJSONObject(i), actual.getJSONObject(i))
    }

    private fun assertJsonEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
        for (key in expected.keys()) {
            val e = expected.get(key)
            val a = actual.get(key)
            when (e) {
                is JSONObject -> assertJsonEquals(e, a as JSONObject)
                is JSONArray -> assertJsonEquals(e, a as JSONArray)
                is Number -> assertEquals(key, e.toLong(), (a as Number).toLong())
                else -> assertEquals(key, e, a)
            }
        }
    }
}
