package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.StartColumns
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.tiles.WidgetTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartColumnsTest {
    private val gap = 4f

    /** The grid width the screen passes: the width between the paddings plus one gap. */
    private fun columns(widthDp: Float, left: Float = 8f, right: Float = 8f) = StartColumns.auto(widthDp - left - right + gap)

    @Test
    fun phoneWidthsNeedTheMinimum() {
        assertEquals(4, columns(411f))
        assertEquals(4, columns(360f))
        assertEquals(4, columns(100f))
    }

    @Test
    fun tabletsGetTheSmallestEvenCountWithCellsAtMostTheCap() {
        assertEquals(8, columns(800f)) // portrait: 788 / 8 = 98.5dp
        assertEquals(14, columns(1280f)) // landscape: 1268 / 14 = 90.6dp
        assertEquals(10, columns(1000f))
        assertEquals(6, columns(600f)) // 596 / 6 = 99.3dp
    }

    @Test
    fun foldablesAndSplitScreens() {
        assertEquals(8, columns(673f)) // inner screen of a small foldable, portrait: 661 / 8 = 82.6dp
        assertEquals(8, columns(841f))
        assertEquals(6, columns(640f, left = 24f, right = 24f))
    }

    @Test
    fun cellsNeverExceedTheCapUntilTheColumnLimit() {
        for (width in 300..2000) {
            val n = columns(width.toFloat())
            assertTrue(n % 2 == 0 && n in 4..StartColumns.MAX_AUTO)
            val cell = (width - 16f + gap) / n
            if (n < StartColumns.MAX_AUTO) assertTrue("$width: $cell", cell <= WidgetTiles.MAX_CELL_DP + 0.01f)
            // The next smaller even count would have been too wide.
            if (n > 4) assertTrue("$width", (width - 16f + gap) / (n - 2) > WidgetTiles.MAX_CELL_DP)
        }
    }

    @Test
    fun wideScreensAreCapped() {
        assertEquals(StartColumns.MAX_AUTO, columns(5000f))
    }

    @Test
    fun autoOnlyAppliesToLargeScreens() {
        val auto = LauncherSettings.AUTO_COLUMNS
        assertEquals(14, StartColumns.resolve(auto, large = true, widthDp = 1268f))
        assertEquals(4, StartColumns.resolve(auto, large = false, widthDp = 1268f))
        assertEquals(6, StartColumns.resolve(6, large = true, widthDp = 1268f))
        assertEquals(8, StartColumns.resolve(8, large = false, widthDp = 400f))
    }

    @Test
    fun autoSurvivesTheClamp() {
        assertEquals(LauncherSettings.AUTO_COLUMNS, LauncherSettings.clampColumns(LauncherSettings.AUTO_COLUMNS))
        assertEquals(4, LauncherSettings.clampColumns(-3))
        assertEquals(LauncherSettings.AUTO_COLUMNS, LauncherSettings().columns)
    }

    // --- Rotation: columns shrink and grow ------------------------------------------------------

    private fun tile(id: Long, size: TileSize, x: Int, y: Int) = TileSpec(id, size, TileContent.Weather, x, y)

    private fun wide() = listOf(
        tile(1, TileSize.MEDIUM, 0, 0), tile(2, TileSize.WIDE, 2, 0), tile(3, TileSize.MEDIUM, 6, 0),
        tile(4, TileSize.MEDIUM, 10, 0), tile(5, TileSize.SMALL, 12, 0), tile(6, TileSize.SMALL, 13, 0),
        tile(7, TileSize.WIDE, 8, 2), tile(8, TileSize.MEDIUM, 0, 2),
    )

    @Test
    fun shrinkingPlacesEveryTileInsideAndWithoutOverlap() {
        val specs = wide()
        for (columns in 4..14 step 2) {
            val placements = TilePlacement.layout(specs, columns)
            placements.forEachIndexed { i, p ->
                assertTrue("$columns: $p", p.x >= 0 && p.x + p.width <= columns)
                for (j in i + 1 until placements.size) assertFalse("$columns: $p ${placements[j]}", p.overlaps(placements[j]))
            }
        }
    }

    @Test
    fun shrinkThenGrowRestoresTheWideArrangementWithoutEditing() {
        val stored = wide()
        val settled = TilePlacement.layout(stored, 14)
        assertEquals(stored.map { Placement(it.x, it.y, it.width, it.height) }, settled)
        val narrow = TilePlacement.layout(stored, 6)
        assertTrue(narrow != settled)
        // Nothing is written back by showing it, so the stored positions still describe the wide grid.
        assertEquals(settled, TilePlacement.layout(stored, 14))
        // Loading the saved layout settles it for the widest grid, which leaves a settled one alone.
        val loaded = TilePlacement.settle(TilePlacement.clampPositions(stored, StartColumns.MAX_AUTO), StartColumns.MAX_AUTO)
        assertEquals(stored, loaded)
    }
}
