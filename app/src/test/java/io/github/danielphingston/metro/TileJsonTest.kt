package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
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
    fun everyTileKindRoundTrips() {
        val app = AppKey("com.example", "com.example.Main", 0)
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, TileContent.App(app)),
            TileSpec(2, TileSize.SMALL, TileContent.Shortcut("com.example", "compose", 0, "New message")),
            TileSpec(3, TileSize.WIDE, TileContent.Widget(42, "com.example/.Widget", 10)),
            TileSpec(4, TileSize.WIDE, TileContent.Weather),
            TileSpec(5, TileSize.LARGE, TileContent.Photos),
            TileSpec(6, TileSize.MEDIUM, TileContent.People),
            TileSpec(7, TileSize.MEDIUM, TileContent.Folder("games", listOf(TileSpec(8, TileSize.SMALL, TileContent.App(app))))),
        )
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
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
