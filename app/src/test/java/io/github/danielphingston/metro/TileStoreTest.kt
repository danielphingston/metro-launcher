package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStore
import io.github.danielphingston.metro.data.WidgetPruning
import io.github.danielphingston.metro.ui.start.TilePlacement
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TileStoreTest {
    private val app = AppKey("com.example", "com.example.Main", 0)
    private val good = TileJson.encode(listOf(TileSpec(1, TileSize.MEDIUM, TileContent.App(app), x = 0, y = 0))).toString()

    @Test
    fun aCorruptLayoutFallsBackToTheBackup() {
        val loaded = TileStore.load("[{\"id\":1,\"type\":", good)
        assertEquals(1, loaded.tiles.size)
    }

    @Test
    fun aCorruptLayoutWithoutABackupIsEmpty() {
        assertTrue(TileStore.load("not json", null).tiles.isEmpty())
        assertTrue(TileStore.load("not json", "also not").tiles.isEmpty())
    }

    @Test
    fun aGoodLayoutIgnoresTheBackup() {
        assertEquals(1, TileStore.load(good, "[]").tiles.size)
        assertTrue(TileStore.load("[]", good).tiles.isEmpty())
    }

    @Test
    fun readableMeansAJsonArray() {
        assertTrue(TileJson.isReadable(good))
        assertFalse(TileJson.isReadable("{"))
        assertNull(TileJson.read("{"))
    }

    @Test
    fun undecodableEntriesSurviveASaveUnchanged() {
        val saved = """[
            {"id":1,"type":"quick","kind":"flashlight","size":"SMALL"},
            {"id":2,"type":"quick","kind":"teleporter","size":"SMALL","x":2,"y":0},
            {"id":3,"type":"folder","name":"f","size":"MEDIUM","children":[
                {"id":4,"type":"hologram","size":"SMALL"},
                {"id":5,"type":"quick","kind":"battery","size":"SMALL"}
            ]}
        ]"""
        val read = TileJson.read(saved)!!
        assertEquals(listOf(1L, 3L), read.tiles.map { it.id })
        assertEquals(QuickSettingKind.FLASHLIGHT, (read.tiles[0].content as TileContent.QuickSetting).kind)

        val rewritten = JSONArray(TileJson.encode(read.tiles, read.unread).toString())
        val ids = (0 until rewritten.length()).map { rewritten.getJSONObject(it).getLong("id") }
        assertEquals(listOf(1L, 3L, 2L), ids)
        assertEquals("teleporter", rewritten.getJSONObject(2).getString("kind"))
        val children = rewritten.getJSONObject(1).getJSONArray("children")
        assertEquals(listOf(5L, 4L), (0 until children.length()).map { children.getJSONObject(it).getLong("id") })
        // And reading the result again gives the same tiles.
        assertEquals(read.tiles, TileJson.decode(rewritten))
    }

    @Test
    fun hugePositionsAreClampedOnDecode() {
        val saved = """[{"id":1,"type":"people","size":"MEDIUM","x":2000000000,"y":2000000000}]"""
        val tile = TileJson.decode(JSONArray(saved)).single()
        assertTrue(tile.x in 0..64)
        assertTrue(tile.y in 0..4096)
    }

    @Test
    fun migrateBoundsPositionsToTheGridAndTheTileCount() {
        val far = TileSpec(1, TileSize.MEDIUM, TileContent.People, x = 40, y = 4000)
        val clamped = TilePlacement.migrate(listOf(far), columns = 4).single()
        assertEquals(3, clamped.x)
        assertEquals(16, clamped.y)
    }

    @Test
    fun aFarOffTileSettlesQuickly() {
        val specs = listOf(
            TileSpec(1, TileSize.MEDIUM, TileContent.People, x = 0, y = 0),
            TileSpec(2, TileSize.MEDIUM, TileContent.Photos, x = 0, y = 2_000_000_000),
        )
        val start = System.nanoTime()
        val placements = TilePlacement.layout(specs, 4)
        assertEquals(2, placements[1].y)
        assertTrue("took too long", System.nanoTime() - start < 2_000_000_000L)
    }

    @Test
    fun widgetsAreOnlyDroppedWhenTheSystemClearlyDoesNotKnowThem() {
        val ids = intArrayOf(7)
        assertTrue(WidgetPruning.isDead(true, infoKnown = false, providerInstalled = true, idsOfProvider = ids, id = 9))
        assertFalse(WidgetPruning.isDead(true, infoKnown = true, providerInstalled = true, idsOfProvider = ids, id = 9))
        assertFalse(WidgetPruning.isDead(true, infoKnown = false, providerInstalled = false, idsOfProvider = ids, id = 9))
        assertFalse(WidgetPruning.isDead(true, infoKnown = false, providerInstalled = true, idsOfProvider = ids, id = 7))
        assertFalse(WidgetPruning.isDead(true, infoKnown = false, providerInstalled = true, idsOfProvider = null, id = 9))
        assertFalse(WidgetPruning.isDead(false, infoKnown = false, providerInstalled = true, idsOfProvider = ids, id = 9))
    }
}
