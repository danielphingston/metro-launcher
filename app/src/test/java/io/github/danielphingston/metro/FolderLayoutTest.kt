package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSize.LARGE
import io.github.danielphingston.metro.data.TileSize.MEDIUM
import io.github.danielphingston.metro.data.TileSize.SMALL
import io.github.danielphingston.metro.data.TileSize.WIDE
import io.github.danielphingston.metro.ui.start.FolderLayout
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.StartLayout
import io.github.danielphingston.metro.ui.start.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLayoutTest {
    private fun packed(vararg sizes: TileSize, columns: Int = 4) = TileGrid.pack(sizes.toList(), columns)

    @Test
    fun closedFolderLeavesTheTilesWhereTheyAre() {
        val layout = FolderLayout.compute(packed(MEDIUM, MEDIUM, WIDE), openIndex = null, childSizes = listOf(SMALL), columns = 4)
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 4, 2)), layout.tiles)
        assertNull(layout.region)
        assertEquals(4, layout.rows)
    }

    /** Checks what every open folder promises: the region hugs the folder, nothing overlaps, tiles above stay put and none change column. */
    private fun assertWellFormed(base: List<Placement>, openIndex: Int, layout: StartLayout, columns: Int) {
        val folder = base[openIndex]
        val cut = folder.y + folder.height
        val region = layout.region!!
        assertEquals(Placement(0, cut, columns, region.height), region)
        val all = layout.tiles + layout.children
        for (i in all.indices) for (j in i + 1 until all.size) assertFalse("${all[i]} overlaps ${all[j]}", all[i].overlaps(all[j]))
        for (tile in layout.tiles) assertFalse("$tile overlaps the region", tile.overlaps(region))
        for (child in layout.children) {
            assertTrue(child.y >= region.y && child.y + child.height <= region.y + region.height)
            assertTrue(child.x >= 0 && child.x + child.width <= columns)
        }
        base.zip(layout.tiles).forEach { (before, after) ->
            assertEquals(before.x, after.x)
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            if (before.y + before.height <= cut) assertEquals(before, after) else assertTrue(after.y >= cut + region.height)
        }
        assertTrue(layout.rows >= TileGrid.rows(layout.tiles))
    }

    @Test
    fun childrenOpenUnderTheFolderAndPushTheRestDown() {
        // Folder (0,0) and a medium beside it share rows 0-1; the wide below moves down by the region.
        val base = packed(MEDIUM, MEDIUM, WIDE)
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = listOf(MEDIUM, MEDIUM, WIDE), columns = 4)
        assertEquals(Placement(0, 2, 4, 4), layout.region)
        assertEquals(
            listOf(Placement(0, 2, 2, 2), Placement(2, 2, 2, 2), Placement(0, 4, 4, 2)),
            layout.children,
        )
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 6, 4, 2)), layout.tiles)
        assertEquals(8, layout.rows)
        assertWellFormed(base, 0, layout, 4)
    }

    @Test
    fun aFolderInTheTopRowOpensRightUnderItself() {
        val base = listOf(Placement(0, 0, 1, 1), Placement(1, 0, 1, 1), Placement(0, 1, 1, 1), Placement(2, 0, 2, 2))
        val layout = FolderLayout.compute(base, openIndex = 1, childSizes = listOf(SMALL, SMALL), columns = 4)
        assertEquals(Placement(0, 1, 4, 1), layout.region)
        // The small tile under the folder and the medium beside it (which straddles row 1) both go below the region.
        assertEquals(listOf(Placement(0, 0, 1, 1), Placement(1, 0, 1, 1), Placement(0, 2, 1, 1), Placement(2, 2, 2, 2)), layout.tiles)
        assertWellFormed(base, 1, layout, 4)
    }

    @Test
    fun aFolderOnAnOddRowOpensRightUnderItself() {
        // Rows don't snap: the folder starts on row 1, so the region starts on row 3 rather than 4.
        val base = listOf(Placement(0, 0, 4, 1), Placement(0, 1, 2, 2), Placement(2, 1, 1, 1), Placement(0, 3, 4, 2))
        val layout = FolderLayout.compute(base, openIndex = 1, childSizes = listOf(MEDIUM, MEDIUM), columns = 4)
        assertEquals(Placement(0, 3, 4, 2), layout.region)
        assertEquals(listOf(Placement(0, 0, 4, 1), Placement(0, 1, 2, 2), Placement(2, 1, 1, 1), Placement(0, 5, 4, 2)), layout.tiles)
        assertWellFormed(base, 1, layout, 4)
    }

    @Test
    fun aTallNeighbourMovesBelowTheRegionInItsColumn() {
        // On six columns a large tile beside the folder runs down to row 4; the region still opens at row 2.
        val base = packed(MEDIUM, LARGE, MEDIUM, columns = 6)
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = listOf(MEDIUM), columns = 6)
        assertEquals(Placement(0, 2, 6, 2), layout.region)
        assertEquals(Placement(2, 4, 4, 4), layout.tiles[1])
        assertEquals(Placement(0, 0, 2, 2), layout.tiles[0])
        assertWellFormed(base, 0, layout, 6)
    }

    @Test
    fun aTallNeighbourKeepsTheTilesBelowItBelowIt() {
        // The tall tile moves to row 4, so the tile that sat under it (row 4 -> 6 by the shift) is pushed to row 8.
        val base = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 4), Placement(2, 4, 2, 2), Placement(0, 4, 2, 2))
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = listOf(MEDIUM), columns = 4)
        assertEquals(Placement(0, 2, 4, 2), layout.region)
        assertEquals(
            listOf(Placement(0, 0, 2, 2), Placement(2, 4, 2, 4), Placement(2, 8, 2, 2), Placement(0, 6, 2, 2)),
            layout.tiles,
        )
        assertWellFormed(base, 0, layout, 4)
    }

    @Test
    fun aShorterNeighbourStaysWhereItIs() {
        // Small tiles beside the folder end at row 1, above the cut at row 2, so they don't move.
        val base = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 1, 1), Placement(3, 0, 1, 1), Placement(2, 1, 1, 1), Placement(0, 2, 4, 2))
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = listOf(WIDE), columns = 4)
        assertEquals(Placement(0, 2, 4, 2), layout.region)
        assertEquals(base.take(4), layout.tiles.take(4))
        assertEquals(Placement(0, 4, 4, 2), layout.tiles[4])
        assertWellFormed(base, 0, layout, 4)
    }

    @Test
    fun aSixColumnFolderOpensFullWidth() {
        val base = packed(WIDE, MEDIUM, MEDIUM, MEDIUM, columns = 6)
        val layout = FolderLayout.compute(base, openIndex = 1, childSizes = listOf(MEDIUM, MEDIUM, MEDIUM, MEDIUM), columns = 6)
        assertEquals(Placement(0, 2, 6, 4), layout.region)
        assertEquals(6, layout.children.maxOf { it.x + it.width })
        assertWellFormed(base, 1, layout, 6)
    }

    @Test
    fun tilesAboveTheFolderAreUntouched() {
        val base = listOf(Placement(0, 0, 4, 2), Placement(0, 2, 2, 2), Placement(2, 2, 1, 1), Placement(0, 4, 4, 2))
        val layout = FolderLayout.compute(base, openIndex = 1, childSizes = listOf(SMALL, SMALL, SMALL), columns = 4)
        assertEquals(Placement(0, 4, 4, 2), layout.region)
        assertEquals(base.take(3), layout.tiles.take(3))
        assertEquals(Placement(0, 6, 4, 2), layout.tiles[3])
        assertWellFormed(base, 1, layout, 4)
    }

    @Test
    fun tilesKeepTheirGapsWhenAFolderOpens() {
        // Positions come from the stored layout, not a repack: the empty cells beside the folder stay empty.
        val base = listOf(Placement(0, 0, 2, 2), Placement(0, 2, 4, 2), Placement(0, 4, 2, 2))
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = listOf(MEDIUM), columns = 4)
        assertEquals(Placement(0, 2, 4, 2), layout.region)
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(0, 4, 4, 2), Placement(0, 6, 2, 2)), layout.tiles)
        assertWellFormed(base, 0, layout, 4)
    }

    @Test
    fun closingTheFolderGivesTheStoredLayoutBack() {
        val base = packed(MEDIUM, LARGE, MEDIUM, columns = 6)
        FolderLayout.compute(base, openIndex = 0, childSizes = listOf(MEDIUM), columns = 6)
        val closed = FolderLayout.compute(base, openIndex = null, childSizes = listOf(MEDIUM), columns = 6)
        assertEquals(base, closed.tiles)
        assertNull(closed.region)
    }

    @Test
    fun anOpenFolderWithoutChildrenStaysClosed() {
        val layout = FolderLayout.compute(packed(MEDIUM, MEDIUM), openIndex = 0, childSizes = emptyList(), columns = 4)
        assertNull(layout.region)
    }
}
