package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSize.LARGE
import io.github.danielphingston.metro.data.TileSize.MEDIUM
import io.github.danielphingston.metro.data.TileSize.SMALL
import io.github.danielphingston.metro.data.TileSize.WIDE
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.start.GridPos
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.TileGrid
import io.github.danielphingston.metro.ui.start.TilePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TilePlacementTest {
    private fun tile(id: Long, size: TileSize, x: Int = TileSpec.UNPLACED, y: Int = TileSpec.UNPLACED, span: TileSpan? = null) =
        TileSpec(id, size, TileContent.Weather, x, y, span)

    private fun layout(vararg specs: TileSpec, columns: Int = 4) = TilePlacement.layout(specs.toList(), columns)

    /** Settled positions by tile id. */
    private fun List<TileSpec>.at(id: Long) = first { it.id == id }.let { GridPos(it.x, it.y) }

    private fun overlap(a: Placement, b: Placement) =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    // --- Stable positions and gravity ---------------------------------------------------------

    @Test
    fun aSettledLayoutIsLeftAlone() {
        val placements = layout(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, WIDE, 0, 2), tile(4, SMALL, 0, 4))
        assertEquals(
            listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 4, 2), Placement(0, 4, 1, 1)),
            placements,
        )
    }

    @Test
    fun tilesSlideStraightUpAndNeverSideways() {
        val placements = layout(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 6))
        assertEquals(Placement(2, 0, 2, 2), placements[1])
    }

    @Test
    fun gravityStopsAtTheTileAbove() {
        val placements = layout(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 0, 6), tile(3, SMALL, 1, 9))
        assertEquals(Placement(0, 2, 2, 2), placements[1])
        assertEquals(Placement(1, 4, 1, 1), placements[2])
    }

    @Test
    fun smallTilesFallARowAtATimeAndBigOnesTwo() {
        val placements = layout(tile(1, SMALL, 0, 0), tile(2, SMALL, 0, 5), tile(3, MEDIUM, 2, 0), tile(4, MEDIUM, 2, 5))
        assertEquals(Placement(0, 1, 1, 1), placements[1])
        // The medium snaps to row 4 and falls two rows at a time, down to just under its neighbour.
        assertEquals(Placement(2, 2, 2, 2), placements[3])
    }

    @Test
    fun emptyCellsBesideATileStayEmpty() {
        // Nothing sits at (2, 0): no tile below slides sideways into it.
        val placements = layout(tile(1, MEDIUM, 0, 0), tile(2, WIDE, 0, 2), tile(3, MEDIUM, 0, 4))
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(0, 2, 4, 2), Placement(0, 4, 2, 2)), placements)
    }

    // --- Snapping, clamping, overlaps ---------------------------------------------------------

    @Test
    fun positionsAreClampedToTheColumnsAndSnappedToEvenCells() {
        assertEquals(Placement(2, 0, 2, 2), layout(tile(1, MEDIUM, 5, 0))[0])
        assertEquals(Placement(0, 0, 2, 2), layout(tile(1, MEDIUM, 1, 0))[0])
        assertEquals(Placement(0, 0, 4, 2), layout(tile(1, WIDE, 2, 0))[0])
        assertEquals(Placement(3, 0, 1, 1), layout(tile(1, SMALL, 9, 0))[0])
        assertEquals(Placement(2, 0, 4, 2), layout(tile(1, WIDE, 5, 0), columns = 6)[0])
        assertEquals(Placement(0, 0, 2, 4), layout(tile(1, LARGE, 0, 0), columns = 2)[0])
    }

    @Test
    fun theLaterTileIsPushedBelowAnOverlap() {
        val placements = layout(tile(1, MEDIUM, 0, 0), tile(2, WIDE, 0, 0))
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(0, 2, 4, 2)), placements)
        // A lower tile that starts inside another is pushed under it, keeping its column.
        assertEquals(Placement(1, 2, 1, 1), layout(tile(1, MEDIUM, 0, 0), tile(2, SMALL, 1, 1))[1])
    }

    @Test
    fun overlapsCascadeDownward() {
        val placements = layout(tile(1, LARGE, 0, 0), tile(2, MEDIUM, 0, 2), tile(3, MEDIUM, 0, 4))
        assertEquals(listOf(Placement(0, 0, 4, 4), Placement(0, 4, 2, 2), Placement(0, 6, 2, 2)), placements)
    }

    @Test
    fun layoutIsIdempotent() {
        val random = Random(7)
        val sizes = TileSize.entries
        repeat(200) { round ->
            val columns = if (round % 2 == 0) 4 else 6
            val specs = List(random.nextInt(1, 14)) { i ->
                val span = if (random.nextInt(4) == 0) TileSpan(random.nextInt(1, 8), random.nextInt(1, 10)) else null
                val placed = random.nextInt(6) != 0
                tile(
                    i + 1L, sizes.random(random),
                    if (placed) random.nextInt(0, 8) else TileSpec.UNPLACED,
                    if (placed) random.nextInt(0, 14) else TileSpec.UNPLACED,
                    span,
                )
            }
            val first = specs.random(random).id.takeIf { random.nextBoolean() }
            val once = TilePlacement.layout(specs, columns, first)
            // Valid: inside the grid, snapped, and nothing overlaps.
            once.forEachIndexed { i, p ->
                assertTrue(p.x >= 0 && p.y >= 0 && p.x + p.width <= columns)
                if (p.width >= 2 && p.width % 2 == 0) assertEquals(0, p.x % 2)
                if (p.height >= 2 && p.height % 2 == 0) assertEquals(0, p.y % 2)
                for (j in i + 1 until once.size) assertFalse("$p overlaps ${once[j]}", overlap(p, once[j]))
            }
            // Settled: no tile can move up, and laying it out again changes nothing.
            once.forEachIndexed { i, p ->
                val step = if (p.height >= 2 && p.height % 2 == 0) 2 else 1
                val free = p.y >= step && once.indices.none { j ->
                    j != i && overlap(Placement(p.x, p.y - step, p.width, step), once[j])
                }
                assertFalse("tile $i could still rise in $once", free)
            }
            assertEquals(once, TilePlacement.layout(TilePlacement.settle(specs, columns, first), columns))
        }
    }

    // --- Resize -------------------------------------------------------------------------------

    @Test
    fun growingATilePushesNeighboursDownWithoutMovingThemSideways() {
        val specs = listOf(tile(1, SMALL, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, MEDIUM, 0, 2), tile(4, MEDIUM, 2, 2))
        val resized = TilePlacement.resize(specs, 1, 4) // small -> wide
        assertEquals(WIDE, resized[0].size)
        assertEquals(GridPos(0, 0), resized.at(1))
        // The tile that was beside it drops below, and the one under that drops further, in the same column.
        assertEquals(GridPos(2, 2), resized.at(2))
        assertEquals(GridPos(0, 2), resized.at(3))
        assertEquals(GridPos(2, 4), resized.at(4))
    }

    @Test
    fun aResizedTileKeepsItsCornerAndSnapsToTheEvenGrid() {
        // A small at (1, 1) becomes wide: its corner snaps to (0, 0) in four columns.
        val specs = listOf(tile(1, SMALL, 1, 1), tile(2, MEDIUM, 0, 2))
        val resized = TilePlacement.resize(specs, 1, 4)
        assertEquals(GridPos(0, 0), resized.at(1))
        assertEquals(GridPos(0, 2), resized.at(2))
        // On six columns a wide tile at x = 5 is clamped to the last even slot, not moved elsewhere.
        val wide = TilePlacement.resize(listOf(tile(1, SMALL, 5, 0)), 1, 6)
        assertEquals(GridPos(2, 0), wide.at(1))
    }

    @Test
    fun shrinkingATileDoesNotMoveUnrelatedTiles() {
        val specs = listOf(
            tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, WIDE, 0, 2), tile(4, MEDIUM, 0, 4), tile(5, MEDIUM, 2, 4),
        )
        val before = TilePlacement.settle(specs, 4)
        val shrunk = TilePlacement.resize(before, 1, 4) // medium -> small
        assertEquals(SMALL, shrunk[0].size)
        // The freed cells stay empty: the wide tile cannot rise (the small and the medium are still above it).
        assertEquals(before.drop(1).map { it.x to it.y }, shrunk.drop(1).map { it.x to it.y })
    }

    @Test
    fun shrinkingLetsTilesDirectlyBelowRise() {
        val specs = listOf(tile(1, LARGE, 0, 0), tile(2, MEDIUM, 0, 4), tile(3, MEDIUM, 2, 4))
        val shrunk = TilePlacement.resize(specs, 1, 4) // large -> medium
        assertEquals(MEDIUM, shrunk[0].size)
        assertEquals(GridPos(0, 2), shrunk.at(2))
        assertEquals(GridPos(2, 0), shrunk.at(3))
    }

    @Test
    fun resizingNeverBackfillsGapsElsewhere() {
        // The bug being fixed: resizing one tile used to reflow the list and drop later tiles into unrelated gaps.
        val before = TilePlacement.settle(
            listOf(
                tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, SMALL, 0, 2),
                tile(4, MEDIUM, 2, 2), tile(5, WIDE, 0, 4), tile(6, SMALL, 0, 6),
            ),
            4,
        )
        val after = TilePlacement.resize(before, 4, 4) // medium -> small
        assertEquals(before.filter { it.id != 4L }.map { it.x to it.y }, after.filter { it.id != 4L }.map { it.x to it.y })
    }

    @Test
    fun resizeStepsThroughTheSizesAndDropsASpan() {
        val specs = listOf(tile(1, MEDIUM, 0, 0, TileSpan(3, 5)))
        val resized = TilePlacement.resize(specs, 1, 4).single()
        assertEquals(SMALL, resized.size)
        assertEquals(null, resized.span)
    }

    // --- Custom spans -------------------------------------------------------------------------

    @Test
    fun aSpanReplacesTheSizeAndOddSpansMayStartOnAnyCell() {
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, WIDE, 1, 1, TileSpan(3, 3)))
        // Overlapping the medium at (1, 1), the 3x3 is pushed under it, but keeps its odd column and row.
        assertEquals(Placement(1, 2, 3, 3), TilePlacement.layout(specs, 6)[1])
        // An even extent still snaps on its axis: 3 wide may sit on column 1, but 2 high sits on even rows.
        val snapped = TilePlacement.layout(listOf(tile(1, MEDIUM, 0, 0), tile(2, WIDE, 1, 3, TileSpan(3, 2))), 6)
        assertEquals(Placement(1, 2, 3, 2), snapped[1])
    }

    @Test
    fun spansAreClampedToTheGrid() {
        val placement = TilePlacement.layout(listOf(tile(1, WIDE, 0, 0, TileSpan(9, 12))), 4).single()
        assertEquals(Placement(0, 0, 4, TileSpan.MAX_HEIGHT), placement)
        assertEquals(Placement(0, 0, 1, 4), TilePlacement.layout(listOf(tile(1, WIDE, 0, 0, TileSpan(1, 4))), 4).single())
    }

    @Test
    fun settingASpanPushesTilesDownLikeAResize() {
        val specs = listOf(tile(1, WIDE, 0, 0), tile(2, MEDIUM, 0, 2), tile(3, MEDIUM, 2, 2))
        val result = TilePlacement.setSpan(specs, 1, 4, 4, 4)
        assertEquals(TileSpan(4, 4), result[0].span)
        assertEquals(GridPos(0, 0), result.at(1))
        assertEquals(GridPos(0, 4), result.at(2))
        assertEquals(GridPos(2, 4), result.at(3))
        // Asking for more than fits is clamped.
        assertEquals(TileSpan(4, TileSpan.MAX_HEIGHT), TilePlacement.setSpan(specs, 1, 20, 20, 4)[0].span)
        assertEquals(TileSpan(1, 1), TilePlacement.setSpan(specs, 1, 0, 0, 4)[0].span)
    }

    @Test
    fun shrinkingASpanLetsTheTilesBelowRise() {
        val specs = TilePlacement.setSpan(listOf(tile(1, WIDE, 0, 0), tile(2, MEDIUM, 0, 2)), 1, 3, 6, 6)
        assertEquals(GridPos(0, 6), specs.at(2))
        assertEquals(GridPos(0, 2), TilePlacement.setSpan(specs, 1, 3, 2, 6).at(2))
    }

    // --- Drag and drop ------------------------------------------------------------------------

    @Test
    fun dropCellRoundsToTheNearestCellOfTheTilesGranularity() {
        val medium = tile(1, MEDIUM)
        val small = tile(1, SMALL)
        assertEquals(GridPos(2, 0), TilePlacement.dropCell(medium, 1.4f, 0.6f, 4))
        assertEquals(GridPos(0, 2), TilePlacement.dropCell(medium, 0.9f, 1.2f, 4))
        assertEquals(GridPos(1, 1), TilePlacement.dropCell(small, 1.4f, 0.6f, 4))
        assertEquals(GridPos(2, 0), TilePlacement.dropCell(medium, 5.6f, -3.4f, 4))
        assertEquals(GridPos(0, 4), TilePlacement.dropCell(tile(1, WIDE), 3f, 3.9f, 4))
        assertEquals(GridPos(3, 0), TilePlacement.dropCell(small, 7f, 0f, 4))
        assertEquals(GridPos(1, 0), TilePlacement.dropCell(tile(1, MEDIUM, span = TileSpan(3, 2)), 0.8f, 0f, 4))
    }

    @Test
    fun droppingOnACellPutsTheTileThereAndPushesTheOccupantDown() {
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, MEDIUM, 0, 2))
        val result = TilePlacement.moveTo(specs, 3, GridPos(2, 0), 4)
        assertEquals(GridPos(2, 0), result.at(3))
        assertEquals(GridPos(2, 2), result.at(2))
        assertEquals(GridPos(0, 0), result.at(1))
    }

    @Test
    fun droppingBelowTheLayoutSettlesUnderTheLastTileOfThatColumn() {
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, MEDIUM, 0, 2))
        val result = TilePlacement.moveTo(specs, 1, GridPos(2, 12), 4)
        assertEquals(GridPos(2, 2), result.at(1))
        // The cell it left is filled by the tile below it rising.
        assertEquals(GridPos(0, 0), result.at(3))
        assertEquals(GridPos(2, 0), result.at(2))
    }

    @Test
    fun theDroppedTileWinsEvenAgainstAnEarlierOne() {
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, SMALL, 0, 2))
        val result = TilePlacement.moveTo(specs, 3, GridPos(1, 0), 4)
        // The small lands on (1, 0), inside the first medium, which goes below it.
        assertEquals(GridPos(1, 0), result.at(3))
        assertEquals(GridPos(0, 2), result.at(1))
    }

    @Test
    fun previewIsTheLayoutOfTheDropWithoutSettlingTheStoredList() {
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0))
        val previewed = TilePlacement.layout(TilePlacement.placeAt(specs, 2, GridPos(0, 0)), 4, first = 2)
        assertEquals(listOf(Placement(0, 2, 2, 2), Placement(0, 0, 2, 2)), previewed)
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2)), TilePlacement.layout(specs, 4))
    }

    // --- Columns ------------------------------------------------------------------------------

    @Test
    fun fewerColumnsClampThenPushOverlapsDown() {
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM, 2, 0), tile(3, MEDIUM, 4, 0), tile(4, SMALL, 5, 0))
        val wide = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(4, 0, 2, 2), Placement(5, 2, 1, 1))
        assertEquals(wide, TilePlacement.layout(specs, 6))
        // The third medium clamps onto the second and goes below it; the small follows below both.
        val narrow = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(2, 2, 2, 2), Placement(3, 4, 1, 1))
        assertEquals(narrow, TilePlacement.layout(specs, 4))
        // The stored positions are untouched, so going back to six columns restores the layout.
        assertEquals(wide, TilePlacement.layout(specs, 6))
    }

    // --- New tiles ----------------------------------------------------------------------------

    @Test
    fun newTilesGoAfterTheLastTileInOrder() {
        val placements = layout(tile(1, MEDIUM), tile(2, MEDIUM), tile(3, MEDIUM), tile(4, SMALL))
        assertEquals(
            listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 2, 2), Placement(2, 2, 1, 1)),
            placements,
        )
    }

    @Test
    fun aNewTileNeverFillsAHoleAboveTheBottom() {
        // (2, 0) is empty beside the first tile, but the wide tile under it closes it off.
        val specs = listOf(tile(1, MEDIUM, 0, 0), tile(2, WIDE, 0, 2), tile(3, MEDIUM), tile(4, SMALL))
        val placements = TilePlacement.layout(specs, 4)
        assertEquals(Placement(0, 4, 2, 2), placements[2])
        assertEquals(Placement(2, 4, 1, 1), placements[3])
    }

    @Test
    fun aNewTileCompletesTheLastRow() {
        val placements = layout(tile(1, MEDIUM, 0, 0), tile(2, MEDIUM), tile(3, SMALL))
        assertEquals(Placement(2, 0, 2, 2), placements[1])
        assertEquals(Placement(0, 2, 1, 1), placements[2])
    }

    @Test
    fun newWideTilesStartOnAnEvenRowBelowTheBottom() {
        val placements = layout(tile(1, SMALL, 0, 0), tile(2, SMALL, 0, 1), tile(3, WIDE))
        assertEquals(Placement(0, 2, 4, 2), placements[2])
    }

    // --- Migration ----------------------------------------------------------------------------

    @Test
    fun migrationGivesPositionsFromTheFirstFitPackingOnce() {
        val sizes = listOf(MEDIUM, SMALL, WIDE, SMALL, SMALL, LARGE, MEDIUM, SMALL, WIDE, MEDIUM, SMALL)
        for (columns in listOf(4, 6)) {
            val legacy = sizes.mapIndexed { i, size -> tile(i + 1L, size) }
            val migrated = TilePlacement.migrate(legacy, columns)
            val packed = TileGrid.pack(sizes, columns)
            assertEquals(packed, migrated.map { Placement(it.x, it.y, it.size.width.coerceAtMost(columns), it.size.height) })
            // Settling it afterwards changes nothing visible.
            assertEquals(packed, TilePlacement.layout(migrated, columns))
            // Already placed layouts are not migrated again.
            assertEquals(migrated, TilePlacement.migrate(migrated, columns))
        }
    }

    @Test
    fun migrationDoesNothingForAnEmptyList() {
        assertEquals(emptyList<TileSpec>(), TilePlacement.migrate(emptyList(), 4))
    }
}
