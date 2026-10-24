package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileFolders
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileFoldersTest {
    private fun app(id: Long, size: TileSize = TileSize.MEDIUM) =
        TileSpec(id, size, TileContent.App(AppKey("com.example.a$id", "Main", 0)))

    private fun folder(id: Long, vararg children: TileSpec, size: TileSize = TileSize.MEDIUM, name: String = "folder") =
        TileSpec(id, size, TileContent.Folder(name, children.toList()))

    private fun TileSpec.children() = (content as TileContent.Folder).children.map { it.id }

    @Test
    fun twoAppsBecomeAFolderWhereTheTargetWas() {
        val list = listOf(app(1), app(2, TileSize.WIDE), app(3), app(4))
        val merged = TileFolders.merge(list, draggedId = 4, targetId = 2, newId = 5)!!
        assertEquals(5L, merged.folderId)
        assertEquals(listOf(1L, 5L, 3L), merged.tiles.map { it.id })
        val created = merged.tiles[1]
        assertEquals(TileSize.WIDE, created.size)
        assertEquals("folder", (created.content as TileContent.Folder).name)
        assertEquals(listOf(2L, 4L), created.children())
    }

    @Test
    fun aSmallTargetGivesAMediumFolder() {
        val merged = TileFolders.merge(listOf(app(1, TileSize.SMALL), app(2, TileSize.SMALL)), 2, 1, newId = 3)!!
        assertEquals(TileSize.MEDIUM, merged.tiles.single().size)
        // The children keep their own sizes.
        val sizes = (merged.tiles.single().content as TileContent.Folder).children.map { it.size }
        assertEquals(listOf(TileSize.SMALL, TileSize.SMALL), sizes)
    }

    @Test
    fun largeChildrenBecomeWide() {
        val merged = TileFolders.merge(listOf(app(1, TileSize.LARGE), app(2, TileSize.LARGE)), 2, 1, newId = 3)!!
        val sizes = (merged.tiles.single().content as TileContent.Folder).children.map { it.size }
        assertEquals(listOf(TileSize.WIDE, TileSize.WIDE), sizes)
        assertEquals(TileSize.LARGE, merged.tiles.single().size)
    }

    @Test
    fun droppingOntoAFolderAppendsAndKeepsItsId() {
        val list = listOf(app(1), folder(2, app(3), app(4)), app(5))
        val merged = TileFolders.merge(list, draggedId = 1, targetId = 2, newId = 6)!!
        assertEquals(2L, merged.folderId)
        assertEquals(listOf(2L, 5L), merged.tiles.map { it.id })
        assertEquals(listOf(3L, 4L, 1L), merged.tiles.first().children())
    }

    @Test
    fun shortcutsMergeButNothingElseDoes() {
        val shortcut = TileSpec(2, TileSize.SMALL, TileContent.Shortcut("com.example", "compose", 0, "New"))
        assertTrue(TileFolders.canMerge(app(1), shortcut))
        assertTrue(TileFolders.canMerge(shortcut, app(1)))
        assertTrue(TileFolders.canMerge(app(1), folder(3, app(4), app(5))))
        // Folders, widgets, weather, photos and people neither go into a folder nor take one.
        assertFalse(TileFolders.canMerge(folder(3, app(4), app(5)), app(1)))
        assertFalse(TileFolders.canMerge(folder(3, app(4), app(5)), folder(6, app(7), app(8))))
        for (content in listOf(
            TileContent.Widget(1, "com.example/.W", 0), TileContent.Weather, TileContent.Photos, TileContent.People,
        )) {
            val other = TileSpec(9, TileSize.WIDE, content)
            assertFalse(TileFolders.canMerge(app(1), other))
            assertFalse(TileFolders.canMerge(other, app(1)))
        }
        assertFalse(TileFolders.canMerge(app(1), app(1)))
        assertNull(TileFolders.merge(listOf(app(1), TileSpec(9, TileSize.WIDE, TileContent.Weather)), 1, 9, 10))
    }

    @Test
    fun removingAChildPutsItAfterTheFolder() {
        val list = listOf(app(1), folder(2, app(3), app(4), app(5)), app(6))
        val result = TileFolders.removeFromFolder(list, folderId = 2, childId = 4)
        assertEquals(listOf(1L, 2L, 4L, 6L), result.map { it.id })
        assertEquals(listOf(3L, 5L), result[1].children())
    }

    @Test
    fun aFolderLeftWithOneChildDissolvesIntoIt() {
        val list = listOf(app(1), folder(2, app(3), app(4)), app(6))
        val result = TileFolders.removeFromFolder(list, folderId = 2, childId = 3)
        assertEquals(listOf(1L, 4L, 3L, 6L), result.map { it.id })
        assertTrue(result.none { it.content is TileContent.Folder })
    }

    @Test
    fun anEmptiedFolderIsRemoved() {
        val result = TileFolders.removeFromFolder(listOf(folder(2, app(3))), folderId = 2, childId = 3)
        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun removeFindsChildrenInsideFolders() {
        val list = listOf(folder(1, app(2), app(3), app(4)), app(5))
        assertEquals(listOf(2L, 4L), TileFolders.remove(list, 3).first().children())
        assertEquals(listOf(1L), TileFolders.remove(list, 5).map { it.id })
        assertEquals(listOf(4L, 5L), TileFolders.remove(TileFolders.remove(list, 3), 2).map { it.id })
    }

    @Test
    fun tidyDissolvesSmallFolders() {
        val list = listOf(folder(1, app(2)), folder(3), folder(4, app(5), app(6)))
        assertEquals(listOf(2L, 4L), TileFolders.tidy(list).map { it.id })
    }

    @Test
    fun renameTrimsAndOnlyTouchesFolders() {
        val list = listOf(app(1), folder(2, app(3), app(4)))
        val renamed = TileFolders.rename(list, 2, "  games ")
        assertEquals("games", (renamed[1].content as TileContent.Folder).name)
        assertEquals(list[0], TileFolders.rename(list, 1, "x")[0])
    }

    @Test
    fun childrenReorderInsideTheirFolder() {
        val list = listOf(folder(1, app(2), app(3), app(4)))
        assertEquals(listOf(4L, 2L, 3L), TileFolders.moveInFolder(list, 1, 4, 2).single().children())
        assertEquals(listOf(3L, 4L, 2L), TileFolders.moveInFolder(list, 1, 2, 4).single().children())
        assertEquals(list, TileFolders.moveInFolder(list, 1, 2, 99))
    }
}
