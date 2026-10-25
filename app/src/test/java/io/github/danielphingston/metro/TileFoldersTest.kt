package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileFolders
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.start.TilePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileFoldersTest {
    private fun app(id: Long, size: TileSize = TileSize.MEDIUM, x: Int = TileSpec.UNPLACED, y: Int = TileSpec.UNPLACED) =
        TileSpec(id, size, TileContent.App(AppKey("com.example.a$id", "Main", 0)), x, y)

    private fun folder(
        id: Long, vararg children: TileSpec, size: TileSize = TileSize.MEDIUM, name: String = "folder",
        x: Int = TileSpec.UNPLACED, y: Int = TileSpec.UNPLACED,
    ) = TileSpec(id, size, TileContent.Folder(name, children.toList()), x, y)

    private fun TileSpec.children() = (content as TileContent.Folder).children.map { it.id }

    private fun group(list: List<TileSpec>, anchor: Long, vararg ids: Long, newId: Long = 99) =
        TileFolders.groupIntoFolder(list, anchor, ids.toList(), newId)

    @Test
    fun anAnchorAndTheTilesPickedBecomeAFolderWhereTheAnchorWas() {
        val list = listOf(app(1), app(2, TileSize.WIDE), app(3), app(4))
        val grouped = group(list, anchor = 2, 4, 1, newId = 5)!!
        assertEquals(5L, grouped.folderId)
        assertEquals(listOf(5L, 3L), grouped.tiles.map { it.id })
        val created = grouped.tiles[0]
        assertEquals(TileSize.WIDE, created.size)
        assertEquals("folder", (created.content as TileContent.Folder).name)
    }

    @Test
    fun theAnchorComesFirstThenThePickedTilesInTheOrderTheyWerePicked() {
        val list = listOf(app(1), app(2), app(3), app(4), app(5))
        assertEquals(listOf(3L, 5L, 2L, 4L), group(list, 3, 5, 2, 4)!!.tiles.first { it.id == 99L }.children())
        assertEquals(listOf(3L, 4L, 2L), group(list, 3, 4, 2)!!.tiles.first { it.id == 99L }.children())
    }

    @Test
    fun anExistingFolderTakesThePickedTilesAfterItsChildrenAndKeepsItsId() {
        val list = listOf(app(1), folder(2, app(3), app(4)), app(5), app(6))
        val grouped = group(list, 2, 6, 1)!!
        assertEquals(2L, grouped.folderId)
        assertEquals(listOf(2L, 5L), grouped.tiles.map { it.id })
        assertEquals(listOf(3L, 4L, 6L, 1L), grouped.tiles.first().children())
        assertEquals("folder", (grouped.tiles.first().content as TileContent.Folder).name)
    }

    @Test
    fun aSmallAnchorGivesAMediumFolder() {
        val grouped = group(listOf(app(1, TileSize.SMALL), app(2, TileSize.SMALL)), 1, 2, newId = 3)!!
        assertEquals(TileSize.MEDIUM, grouped.tiles.single().size)
        // The children keep their own sizes.
        val sizes = (grouped.tiles.single().content as TileContent.Folder).children.map { it.size }
        assertEquals(listOf(TileSize.SMALL, TileSize.SMALL), sizes)
    }

    @Test
    fun otherAnchorSizesAreKeptAndLargeChildrenBecomeWide() {
        assertEquals(TileSize.WIDE, group(listOf(app(1, TileSize.WIDE), app(2)), 1, 2)!!.tiles.single().size)
        val grouped = group(listOf(app(1, TileSize.LARGE), app(2, TileSize.LARGE)), 1, 2)!!
        val sizes = (grouped.tiles.single().content as TileContent.Folder).children.map { it.size }
        assertEquals(listOf(TileSize.WIDE, TileSize.WIDE), sizes)
        assertEquals(TileSize.LARGE, grouped.tiles.single().size)
    }

    @Test
    fun shortcutsCanBeGroupedButNothingElseCan() {
        val shortcut = TileSpec(2, TileSize.SMALL, TileContent.Shortcut("com.example", "compose", 0, "New"))
        val grouped = group(listOf(app(1), shortcut), 1, 2)!!
        assertEquals(listOf(1L, 2L), grouped.tiles.single().children())
        assertEquals(listOf(2L, 1L), group(listOf(app(1), shortcut), 2, 1)!!.tiles.single().children())
        // Only apps, shortcuts and folders start a group; only apps and shortcuts join one.
        assertTrue(TileFolders.canGroup(app(1)) && TileFolders.canGroup(shortcut))
        assertTrue(TileFolders.canGroup(folder(3, app(4), app(5))))
        assertFalse(TileFolders.canHold(folder(3, app(4), app(5))))
        val others = listOf(
            TileContent.Widget(1, "com.example/.W", 0), TileContent.Weather, TileContent.Photos, TileContent.People,
        ).map { TileSpec(9, TileSize.WIDE, it) }
        for (other in others) {
            assertFalse(TileFolders.canGroup(other))
            assertNull(group(listOf(app(1), other), 9, 1))
            assertNull(group(listOf(app(1), other), 1, 9))
        }
    }

    @Test
    fun ineligibleMissingAndRepeatedIdsAreIgnored() {
        val weather = TileSpec(8, TileSize.WIDE, TileContent.Weather)
        val list = listOf(app(1), app(2), app(3), weather, folder(4, app(5), app(6)))
        val grouped = group(list, 1, 8, 4, 5, 77, 1, 3, 3, 2)!!
        // Not the widget, the folder (no nesting), a folder child, an unknown id, the anchor or a second 3.
        assertEquals(listOf(1L, 3L, 2L), grouped.tiles.first { it.id == 99L }.children())
        assertEquals(listOf(99L, 8L, 4L), grouped.tiles.map { it.id })
        assertNull(group(list, 1, 8, 4, 77, 1))
        assertNull(group(list, 1))
        assertNull(group(list, 77, 2))
    }

    @Test
    fun aFolderCannotBeGroupedIntoAnotherFolder() {
        val list = listOf(folder(1, app(2), app(3)), folder(4, app(5), app(6)))
        assertNull(group(list, 1, 4))
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

    @Test
    fun aNewFolderTakesTheAnchorsGridPosition() {
        val list = listOf(app(1, x = 0, y = 0), app(2, TileSize.WIDE, x = 0, y = 2), app(3, x = 0, y = 4))
        val grouped = group(list, 2, 3, newId = 5)!!
        val created = grouped.tiles.single { it.id == 5L }
        assertEquals(0 to 2, created.x to created.y)
        // The children have no position of their own, and the other tiles keep theirs.
        val children = (created.content as TileContent.Folder).children
        assertTrue(children.none { it.isPlaced })
        assertEquals(0 to 0, grouped.tiles.first().let { it.x to it.y })
    }

    @Test
    fun addingToAFolderKeepsItsPosition() {
        val list = listOf(app(1, x = 0, y = 0), folder(2, app(3), app(4), x = 2, y = 0))
        val grouped = group(list, 2, 1)!!
        assertEquals(2 to 0, grouped.tiles.single().let { it.x to it.y })
        assertTrue((grouped.tiles.single().content as TileContent.Folder).children.none { it.isPlaced })
    }

    @Test
    fun tilesBelowRiseIntoTheSpaceTheGroupedTilesLeave() {
        val list = listOf(
            app(1, x = 0, y = 0), app(2, x = 2, y = 0), app(3, x = 0, y = 2), app(4, x = 2, y = 2), app(5, x = 0, y = 4),
        )
        val grouped = group(list, 1, 2, 3, newId = 6)!!
        val settled = TilePlacement.settle(grouped.tiles, 4, first = grouped.folderId)
        assertEquals(listOf(6L, 4L, 5L), settled.map { it.id })
        assertEquals(listOf(0 to 0, 2 to 0, 0 to 2), settled.map { it.x to it.y })
    }

    @Test
    fun aDissolvedFolderLeavesItsLastChildInItsSpot() {
        val list = listOf(app(1, x = 0, y = 0), folder(2, app(3), app(4), x = 2, y = 2))
        val result = TileFolders.removeFromFolder(list, folderId = 2, childId = 3)
        assertEquals(listOf(1L, 4L, 3L), result.map { it.id })
        assertEquals(2 to 2, result[1].let { it.x to it.y })
        // The child that was taken out is a new tile: unplaced, so it lands at the bottom.
        assertFalse(result[2].isPlaced)
        // Removing a child while others remain leaves the folder where it was.
        val kept = TileFolders.removeFromFolder(listOf(folder(2, app(3), app(4), app(5), x = 2, y = 2)), 2, 3)
        assertEquals(2 to 2, kept.first().let { it.x to it.y })
        assertFalse(kept.last().isPlaced)
        // Unpinning a child out of a two-child folder dissolves it the same way.
        assertEquals(2 to 2, TileFolders.remove(list, 3)[1].let { it.x to it.y })
    }
}
