package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileSize.LARGE
import io.github.danielphingston.metro.data.TileSize.MEDIUM
import io.github.danielphingston.metro.data.TileSize.SMALL
import io.github.danielphingston.metro.data.TileSize.WIDE
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TileGridTest {
    @Test
    fun mediumsFillRowsTwoAcross() {
        assertEquals(
            listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 2, 2)),
            TileGrid.pack(listOf(MEDIUM, MEDIUM, MEDIUM), columns = 4),
        )
    }

    @Test
    fun smallTilesClusterInsideAMediumSlot() {
        val placements = TileGrid.pack(listOf(SMALL, SMALL, SMALL, SMALL, MEDIUM), columns = 4)
        assertEquals(
            listOf(
                Placement(0, 0, 1, 1), Placement(1, 0, 1, 1), Placement(0, 1, 1, 1), Placement(1, 1, 1, 1),
                Placement(2, 0, 2, 2),
            ),
            placements,
        )
    }

    @Test
    fun mediumTilesSnapToEvenCells() {
        // A medium after one small tile must not straddle columns 1-2.
        val placements = TileGrid.pack(listOf(SMALL, MEDIUM), columns = 4)
        assertEquals(Placement(2, 0, 2, 2), placements[1])
    }

    @Test
    fun laterSmallTilesFillEarlierGaps() {
        val placements = TileGrid.pack(listOf(MEDIUM, WIDE, SMALL), columns = 4)
        assertEquals(Placement(0, 2, 4, 2), placements[1])
        assertEquals(Placement(2, 0, 1, 1), placements[2])
    }

    @Test
    fun wideTilesAreClampedOnNarrowGrids() {
        assertEquals(Placement(0, 0, 2, 4), TileGrid.pack(listOf(LARGE), columns = 2).single())
    }

    @Test
    fun noTilesOverlap() {
        val sizes = listOf(MEDIUM, SMALL, WIDE, SMALL, SMALL, LARGE, MEDIUM, SMALL, WIDE, MEDIUM, SMALL)
        for (columns in listOf(4, 6)) {
            val cells = TileGrid.pack(sizes, columns).flatMap { p ->
                (p.x until p.x + p.width).flatMap { x -> (p.y until p.y + p.height).map { y -> x to y } }
            }
            assertEquals(cells.size, cells.toSet().size)
            assertFalse(cells.any { it.first >= columns })
        }
    }
}
