package io.github.danielphingston.metro

import android.content.Intent
import android.provider.AlarmClock
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.LaunchTarget
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStyle
import io.github.danielphingston.metro.data.TileStyles
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.TileGrid
import io.github.danielphingston.metro.ui.start.TilePlacement
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is an Android API; Robolectric provides the real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TileJsonTest {
    @Test
    fun tileStyleAndPhotoRoundTrip() {
        val app = TileContent.App(AppKey("a.b", "a.b.Main", 0))
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, app, 0, 0, style = TileStyle.FULL_IMAGE, image = "content://media/1"),
            TileSpec(2, TileSize.SMALL, app, 2, 0, style = TileStyle.NAME_ONLY),
            TileSpec(3, TileSize.SMALL, app, 3, 0),
        )
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
        // An unknown style from a newer build reads as the plain icon tile.
        val unknown = """[{"id":4,"size":"SMALL","type":"app","app":"a.b/a.b.Main#0","style":"sparkles"}]"""
        assertEquals(TileStyle.ICON, TileJson.decode(JSONArray(unknown)).single().style)
    }

    @Test
    fun settingAPlainStyleDropsThePhoto() {
        val app = TileContent.App(AppKey("a.b", "a.b.Main", 0))
        val tiles = listOf(TileSpec(1, TileSize.MEDIUM, app, 0, 0, style = TileStyle.FULL_IMAGE, image = "content://x"))
        val plain = TileStyles.set(tiles, 1, TileStyle.ICON_ONLY, "content://x").single()
        assertEquals(TileStyle.ICON_ONLY, plain.style)
        assertEquals(null, plain.image)
    }

    @Test
    fun everyTileKindRoundTrips() {
        val app = AppKey("com.example", "com.example.Main", 0)
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, TileContent.App(app)),
            TileSpec(2, TileSize.SMALL, TileContent.Shortcut("com.example", "compose", 0, "New message")),
            TileSpec(3, TileSize.WIDE, TileContent.Widget(42, "com.example/.Widget", 10)),
            TileSpec(4, TileSize.WIDE, TileContent.Weather),
            TileSpec(5, TileSize.LARGE, TileContent.Photos),
            TileSpec(6, TileSize.MEDIUM, TileContent.People),
            TileSpec(9, TileSize.WIDE, TileContent.Assistant),
            TileSpec(7, TileSize.MEDIUM, TileContent.Folder("games", listOf(TileSpec(8, TileSize.SMALL, TileContent.App(app))))),
        )
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
    }

    @Test
    fun actionTilesRoundTripWithTheirTargetAndIcon() {
        val app = AppKey("com.example", "com.example.Main", 0)
        val timer = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 300).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, TileContent.Action("timer 5 minutes", ActionIcon.Glyph("timer"), LaunchTarget.Activity(timer))),
            TileSpec(2, TileSize.SMALL, TileContent.Action("cats", ActionIcon.App(app), LaunchTarget.Web("https://example.com/?q=cats"))),
            TileSpec(3, TileSize.MEDIUM, TileContent.Action("example.com", null, LaunchTarget.Web("https://example.com", inBrowser = false))),
            TileSpec(4, TileSize.WIDE, TileContent.Action("Anna", ActionIcon.Contact("content://com.android.contacts/contacts/lookup/k1/1"), LaunchTarget.Web("https://example.com"))),
        )
        val decoded = TileJson.decode(JSONArray(TileJson.encode(tiles).toString()))
        assertEquals(tiles, decoded)
        // The extras survive the intent: URI, so the timer still starts with its length.
        val intent = ((decoded[0].content as TileContent.Action).target as LaunchTarget.Activity).intent
        assertEquals(300, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertEquals(false, intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }

    @Test
    fun actionTilesWithAnUnknownTargetAreKeptUnread() {
        val saved = """[{"id":1,"type":"action","label":"x","size":"SMALL","target":{"kind":"teleport"}}]"""
        val read = TileJson.read(saved)!!
        assertEquals(emptyList<TileSpec>(), read.tiles)
        assertEquals(1, read.unread.top.size)
    }

    @Test
    fun everyQuickSettingRoundTrips() {
        val tiles = QuickSettingKind.entries.mapIndexed { i, kind ->
            TileSpec(i + 1L, TileSize.MEDIUM, TileContent.QuickSetting(kind), x = i % 4, y = i / 4 * 2)
        }
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
    }

    @Test
    fun quickSettingIdsAreUnique() {
        assertEquals(QuickSettingKind.entries.size, QuickSettingKind.entries.map { it.id }.toSet().size)
    }

    @Test
    fun unknownQuickSettingKindsAndTileTypesAreDroppedWithoutLosingTheRest() {
        val saved = """[
            {"id":1,"type":"quick","kind":"flashlight","size":"SMALL"},
            {"id":2,"type":"quick","kind":"teleporter","size":"SMALL"},
            {"id":3,"type":"quick","size":"SMALL"},
            {"id":4,"type":"hologram","size":"WIDE"},
            {"id":5,"type":"people","size":"MEDIUM"}
        ]"""
        assertEquals(
            listOf(
                TileSpec(1, TileSize.SMALL, TileContent.QuickSetting(QuickSettingKind.FLASHLIGHT)),
                TileSpec(5, TileSize.MEDIUM, TileContent.People),
            ),
            TileJson.decode(JSONArray(saved)),
        )
    }

    @Test
    fun aSavedTileOfARemovedQuickKindLoadsWithoutIt() {
        val saved = """[
            {"id":1,"type":"quick","kind":"wifi","size":"SMALL"},
            {"id":2,"type":"quick","kind":"battery","size":"SMALL"}
        ]"""
        assertEquals(
            listOf(TileSpec(2, TileSize.SMALL, TileContent.QuickSetting(QuickSettingKind.BATTERY))),
            TileJson.decode(JSONArray(saved)),
        )
    }

    @Test
    fun readsVersionOneLayouts() {
        val legacy = """[{"id":1,"app":"com.example/com.example.Main#0","size":"WIDE"}]"""
        assertEquals(
            listOf(TileSpec(1, TileSize.WIDE, TileContent.App(AppKey("com.example", "com.example.Main", 0)))),
            TileJson.decode(JSONArray(legacy)),
        )
    }

    @Test
    fun positionsAndSpansRoundTrip() {
        val app = AppKey("com.example", "com.example.Main", 0)
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, TileContent.App(app), x = 2, y = 4),
            TileSpec(2, TileSize.WIDE, TileContent.Widget(42, "com.example/.Widget", 10), x = 0, y = 0, span = TileSpan(3, 5)),
            TileSpec(3, TileSize.MEDIUM, TileContent.Folder("games", listOf(TileSpec(4, TileSize.SMALL, TileContent.App(app)))), x = 0, y = 2),
        )
        val json = TileJson.encode(tiles).toString()
        assertEquals(tiles, TileJson.decode(JSONArray(json)))
        // Folder children carry no position.
        assertEquals(-1, TileJson.decode(JSONArray(json))[2].let { (it.content as TileContent.Folder).children.single().x })
    }

    @Test
    fun layoutsWithoutPositionsDecodeUnplacedAndMigrateFromThePacking() {
        val legacy = """[
            {"id":1,"type":"app","app":"com.example/com.example.A#0","size":"MEDIUM"},
            {"id":2,"type":"app","app":"com.example/com.example.B#0","size":"SMALL"},
            {"id":3,"type":"weather","size":"WIDE"},
            {"id":4,"type":"app","app":"com.example/com.example.C#0","size":"SMALL"}
        ]"""
        val decoded = TileJson.decode(JSONArray(legacy))
        assertEquals(listOf(false, false, false, false), decoded.map { it.isPlaced })
        val migrated = TilePlacement.migrate(decoded, 4)
        assertEquals(
            TileGrid.pack(decoded.map { it.size }, 4),
            migrated.map { Placement(it.x, it.y, it.size.width, it.size.height) },
        )
        // Once migrated and saved, the positions are read back as they are.
        assertEquals(migrated, TileJson.decode(JSONArray(TileJson.encode(migrated).toString())))
    }
}
