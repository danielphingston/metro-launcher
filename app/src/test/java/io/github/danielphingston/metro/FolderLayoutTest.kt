package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSize.LARGE
import io.github.danielphingston.metro.data.TileSize.MEDIUM
import io.github.danielphingston.metro.data.TileSize.SMALL
import io.github.danielphingston.metro.data.TileSize.WIDE
import io.github.danielphingston.metro.ui.start.FolderLayout
import io.github.danielphingston.metro.ui.start.Placement
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

    @Test
    fun childrenOpenUnderTheFolderRowsAndPushTheRestDown() {
        // Folder (0,0) and a medium beside it share rows 0-1; the wide below moves down by the region.
        val layout = FolderLayout.compute(
            base = packed(MEDIUM, MEDIUM, WIDE),
            openIndex = 0,
            childSizes = listOf(MEDIUM, MEDIUM, WIDE),
            columns = 4,
        )
        assertEquals(Placement(0, 2, 4, 4), layout.region)
        assertEquals(
            listOf(Placement(0, 2, 2, 2), Placement(2, 2, 2, 2), Placement(0, 4, 4, 2)),
            layout.children,
        )
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 6, 4, 2)), layout.tiles)
        assertEquals(8, layout.rows)
    }

    @Test
    fun theRegionOpensBelowATallNeighbour() {
        // On six columns a large tile sits beside the folder down to row 4, so the region can't start at row 2.
        val layout = FolderLayout.compute(
            base = packed(MEDIUM, LARGE, MEDIUM, columns = 6),
            openIndex = 0,
            childSizes = listOf(MEDIUM),
            columns = 6,
        )
        assertEquals(Placement(2, 0, 4, 4), layout.tiles[1])
        assertEquals(Placement(0, 4, 6, 2), layout.region)
    }

    @Test
    fun smallClustersAreNotSplit() {
        // A small folder in a 2x2 cluster: the region starts after the whole cluster.
        val layout = FolderLayout.compute(
            base = packed(SMALL, SMALL, SMALL, SMALL, MEDIUM),
            openIndex = 0,
            childSizes = listOf(SMALL, SMALL),
            columns = 4,
        )
        assertEquals(2, layout.region!!.y)
        assertEquals(listOf(0, 0, 1, 1), layout.tiles.take(4).map { it.y })
    }

    @Test
    fun tilesKeepTheirGapsWhenAFolderOpens() {
        // Positions come from the stored layout, not a repack: the empty cells beside the folder stay empty.
        val base = listOf(Placement(0, 0, 2, 2), Placement(0, 2, 4, 2), Placement(0, 4, 2, 2))
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = listOf(MEDIUM), columns = 4)
        assertEquals(Placement(0, 2, 4, 2), layout.region)
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(0, 4, 4, 2), Placement(0, 6, 2, 2)), layout.tiles)
    }

    @Test
    fun anOpenFolderWithoutChildrenStaysClosed() {
        val layout = FolderLayout.compute(packed(MEDIUM, MEDIUM), openIndex = 0, childSizes = emptyList(), columns = 4)
        assertNull(layout.region)
    }

    @Test
    fun middleAreaIsTheCentreHalfOfATile() {
        val medium = Placement(2, 0, 2, 2)
        assertTrue(medium.inMiddle(3f, 1f))
        assertTrue(medium.inMiddle(3.4f, 0.6f))
        assertFalse(medium.inMiddle(2.1f, 1f))
        assertFalse(medium.inMiddle(3f, 1.8f))
        assertFalse(medium.inMiddle(1.9f, 1f))
    }
}
