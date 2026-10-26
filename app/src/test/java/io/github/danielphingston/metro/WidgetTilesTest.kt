package io.github.danielphingston.metro

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.util.SizeF
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.tiles.SpanLimits
import io.github.danielphingston.metro.ui.tiles.WidgetDims
import io.github.danielphingston.metro.ui.tiles.WidgetTiles
import io.github.danielphingston.metro.ui.tiles.matchesWidgetQuery
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Bundle and org.json are Android APIs; Robolectric provides the real implementations.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetTilesTest {
    /** A 411dp wide phone with four columns, as the start screen lays it out. */
    private val cell = WidgetTiles.cellDp(411, 4)

    private fun dims(
        w: Float, h: Float, resizeW: Float = 0f, resizeH: Float = 0f, cols: Int = 0, rows: Int = 0,
        maxW: Float = 0f, maxH: Float = 0f, mode: Int = AppWidgetProviderInfo.RESIZE_BOTH,
    ) = WidgetDims(w, h, resizeW, resizeH, cols, rows, maxW, maxH, mode)

    private fun limits(d: WidgetDims, columns: Int = 4, width: Int = 2, height: Int = 2) =
        WidgetTiles.spanLimits(d, WidgetTiles.cellDp(411, columns), columns, width, height)

    @Test
    fun sizeOptionsFillEveryKeyAWidgetMayRead() {
        val options = WidgetTiles.sizeOptions(SizeF(363.6f, 188.2f))
        assertEquals(363, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
        assertEquals(363, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
        assertEquals(188, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        assertEquals(188, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        assertEquals(
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY),
        )
        @Suppress("DEPRECATION")
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        assertEquals(listOf(SizeF(363.6f, 188.2f)), sizes)
    }

    private fun span(d: WidgetDims, columns: Int = 4) =
        WidgetTiles.suggestedSpan(d, WidgetTiles.cellDp(411, columns), columns)

    @Test
    fun withoutTargetCellsTheSmallestSpanThatHoldsTheMinimumIsUsed() {
        // Cells are 99.75dp at four columns: one cell holds 95.75dp, two 195.5dp, three 295.25dp.
        assertEquals(TileSpan(1, 1), span(dims(40f, 40f)))
        assertEquals(TileSpan(2, 2), span(dims(110f, 110f)))
        assertEquals(TileSpan(3, 2), span(dims(250f, 110f)))
        assertEquals(TileSpan(4, 8), span(dims(900f, 900f)))
        // Exactly a whole number of cells does not round up to the next one.
        assertEquals(TileSpan(2, 1), span(dims(195.5f, 40f)))
        // Six columns have smaller cells (66.5dp).
        assertEquals(TileSpan(2, 2), span(dims(110f, 110f), columns = 6))
        assertEquals(TileSpan(3, 2), span(dims(150f, 110f), columns = 6))
    }

    @Test
    fun spansAreExactNotJustTheFourTileSizes() {
        assertEquals(TileSpan(1, 2), span(dims(60f, 150f)))
        assertEquals(TileSpan(2, 1), span(dims(150f, 60f)))
        assertEquals(TileSpan(1, 2), span(dims(40f, 40f, cols = 1, rows = 2)))
        assertEquals(TileSpan(3, 2), span(dims(40f, 40f, cols = 3, rows = 2)))
    }

    @Test
    fun aOneByOneSamsungWidgetStaysOneByOneAtSixColumns() {
        // The cell is 66.5dp; the widget declares one cell but a minimum of 80dp, within the tolerance.
        val battery = dims(80f, 80f, resizeW = 40f, resizeH = 40f, cols = 1, rows = 1)
        assertEquals(TileSpan(1, 1), span(battery, columns = 6))
        assertEquals(TileSpan(1, 1), span(battery, columns = 4))
        // Without declared target cells the same minimum needs two cells.
        assertEquals(TileSpan(2, 2), span(dims(80f, 80f), columns = 6))
    }

    @Test
    fun aOneWideTwoTallWidgetGetsOneByTwo() {
        val tall = dims(60f, 150f, cols = 1, rows = 2)
        assertEquals(TileSpan(1, 2), span(tall, columns = 6))
        assertEquals(TileSpan(1, 2), span(tall, columns = 4))
    }

    @Test
    fun targetCellsAreHonouredUntilTheMinimumIsTooFarOver() {
        // 66.5dp cells: one cell tolerates 83.125dp.
        val six = 6
        assertEquals(TileSpan(1, 1), span(dims(83f, 83f, cols = 1, rows = 1), six))
        assertEquals(TileSpan(2, 2), span(dims(84f, 84f, cols = 1, rows = 1), six))
        // The axes are decided on their own.
        assertEquals(TileSpan(1, 2), span(dims(83f, 84f, cols = 1, rows = 1), six))
        // Two cells tolerate 166.25dp.
        assertEquals(TileSpan(2, 2), span(dims(166f, 166f, cols = 2, rows = 2), six))
        assertEquals(TileSpan(3, 3), span(dims(167f, 167f, cols = 2, rows = 2), six))
        // The size it can shrink to also counts when it is the bigger one.
        assertEquals(TileSpan(3, 1), span(dims(40f, 40f, resizeW = 190f, cols = 1, rows = 1), six))
        // A target larger than the minimum needs is used as declared.
        assertEquals(TileSpan(4, 2), span(dims(40f, 40f, cols = 4, rows = 2)))
    }

    @Test
    fun theSpanIsKeptWithinTheGrid() {
        assertEquals(TileSpan(4, 8), span(dims(40f, 40f, cols = 6, rows = 12)))
        assertEquals(TileSpan(4, 8), span(dims(2000f, 5000f)))
    }

    @Test
    fun theClosestTileSizeFollowsTheSpan() {
        assertEquals(TileSize.SMALL, WidgetTiles.nearestSize(TileSpan(1, 1)))
        assertEquals(TileSize.MEDIUM, WidgetTiles.nearestSize(TileSpan(2, 2)))
        assertEquals(TileSize.WIDE, WidgetTiles.nearestSize(TileSpan(4, 2)))
        assertEquals(TileSize.WIDE, WidgetTiles.nearestSize(TileSpan(3, 2)))
        assertEquals(TileSize.LARGE, WidgetTiles.nearestSize(TileSpan(4, 8)))
        assertEquals(TileSize.MEDIUM, WidgetTiles.nearestSize(TileSpan(2, 3)))
    }

    @Test
    fun theHandleCanReachTheStartingSpan() {
        val samples = listOf(
            dims(80f, 80f, resizeW = 40f, resizeH = 40f, cols = 1, rows = 1),
            dims(80f, 80f, cols = 1, rows = 1),
            dims(84f, 84f, cols = 1, rows = 1),
            dims(60f, 150f, cols = 1, rows = 2),
            dims(250f, 110f),
            dims(250f, 110f, resizeW = 150f, resizeH = 60f),
            dims(40f, 40f, resizeW = 200f, cols = 1, rows = 1),
            dims(900f, 900f),
        )
        for (columns in listOf(4, 6)) for (d in samples) {
            val start = span(d, columns)
            val l = limits(d, columns, start.width, start.height)
            assertTrue("$d at $columns: $l vs $start", l.minWidth <= start.width && l.minHeight <= start.height)
            assertTrue("$d at $columns: $l vs $start", l.maxWidth >= start.width && l.maxHeight >= start.height)
        }
    }

    @Test
    fun theHandleMinimumUsesTheSameToleranceAsTheStart() {
        // 66.5dp cells: the 80dp minimum fits one cell with tolerance, so the handle goes down to 1x1.
        val d = dims(80f, 80f, cols = 1, rows = 1)
        assertEquals(SpanLimits(1, 6, 1, 8), limits(d, columns = 6, width = 1, height = 1))
        // Without target cells it still starts at 2x2, but can be dragged down to the tolerant minimum.
        val plain = dims(80f, 80f)
        assertEquals(TileSpan(2, 2), span(plain, columns = 6))
        assertEquals(SpanLimits(1, 6, 1, 8), limits(plain, columns = 6))
    }

    @Test
    fun tabletCellsStayPhoneSized() {
        // A phone's cells are below the cap and unchanged.
        assertEquals(99.75f, WidgetTiles.cellDp(411, 4), 0.001f)
        // A 1280dp wide tablet in landscape would otherwise get 317dp cells in four columns.
        assertEquals(WidgetTiles.MAX_CELL_DP, WidgetTiles.cellDp(1280, 4), 0.001f)
        assertEquals(WidgetTiles.MAX_CELL_DP, WidgetTiles.cellDp(1280, 8), 0.001f)
        // A portrait tablet in eight columns fills its width just under the cap.
        assertEquals(98.5f, WidgetTiles.cellDp(800, 8), 0.001f)
    }

    @Test
    fun theSmallestSpanIsTheSizeTheWidgetCanShrinkTo() {
        // Cells are 99.75dp: one cell holds 95.75dp, two 195.5dp, three 295.25dp.
        assertEquals(SpanLimits(2, 4, 1, 8), limits(dims(250f, 110f, resizeW = 150f, resizeH = 60f)))
        // Without a resize minimum the minimum size counts, with a quarter of slack: two cells are 199.5dp, 249.4dp with it.
        assertEquals(SpanLimits(3, 4, 1, 8), limits(dims(255f, 110f)))
        assertEquals(SpanLimits(2, 4, 1, 8), limits(dims(249f, 110f)))
        // Exactly a whole number of cells does not round up to the next one.
        assertEquals(SpanLimits(2, 4, 1, 8), limits(dims(10f, 10f, resizeW = 195.5f)))
        // Never below one cell.
        assertEquals(SpanLimits(1, 4, 1, 8), limits(dims(0f, 0f)))
        // And never beyond the grid.
        assertEquals(SpanLimits(4, 4, 8, 8), limits(dims(900f, 900f)))
    }

    @Test
    fun theLargestSpanIsWhatTheProviderAllowsElseTheWholeGrid() {
        assertEquals(SpanLimits(1, 3, 1, 5), limits(dims(40f, 40f, maxW = 300f, maxH = 500f)))
        assertEquals(SpanLimits(1, 4, 1, 8), limits(dims(40f, 40f)))
        // A maximum above the grid is capped by it.
        assertEquals(SpanLimits(1, 4, 1, 8), limits(dims(40f, 40f, maxW = 2000f, maxH = 5000f)))
        // A maximum below the minimum can't shrink the range to nothing.
        assertEquals(SpanLimits(3, 3, 1, 8), limits(dims(255f, 40f, maxW = 100f)))
        // On six narrower columns (about 66dp) 300dp is four cells.
        assertEquals(SpanLimits(1, 4, 1, 8), limits(dims(40f, 40f, maxW = 300f), columns = 6))
    }

    @Test
    fun anAxisTheWidgetCannotResizeAlongStaysAtTheCurrentSize() {
        val horizontal = dims(40f, 40f, mode = AppWidgetProviderInfo.RESIZE_HORIZONTAL)
        assertEquals(SpanLimits(1, 4, 3, 3), limits(horizontal, height = 3))
        val vertical = dims(40f, 40f, mode = AppWidgetProviderInfo.RESIZE_VERTICAL)
        assertEquals(SpanLimits(4, 4, 1, 8), limits(vertical, width = 4))
        val none = limits(dims(40f, 40f, mode = AppWidgetProviderInfo.RESIZE_NONE), width = 3, height = 2)
        assertEquals(SpanLimits(3, 3, 2, 2), none)
        assertFalse(none.resizable)
        assertTrue(limits(horizontal).resizable)
    }

    @Test
    fun limitsShrinkToTheColumnsLeftOfTheTile() {
        val all = limits(dims(250f, 40f, resizeW = 150f))
        assertEquals(SpanLimits(2, 2, 1, 8), all.within(2))
        // The minimum never exceeds the maximum.
        assertEquals(SpanLimits(1, 1, 1, 8), all.within(1))
    }

    @Test
    fun aHandleDragSnapsToWholeCellsWithinTheLimits() {
        val l = SpanLimits(1, 4, 1, 8)
        assertEquals(TileSpan(2, 2), WidgetTiles.dragSpan(2, 2, 0f, 0f, l))
        assertEquals(TileSpan(2, 2), WidgetTiles.dragSpan(2, 2, 0.49f, -0.49f, l))
        assertEquals(TileSpan(3, 1), WidgetTiles.dragSpan(2, 2, 0.5f, -0.51f, l))
        assertEquals(TileSpan(4, 5), WidgetTiles.dragSpan(2, 2, 1.7f, 2.6f, l))
        // Dragged past the limits it stops there.
        assertEquals(TileSpan(4, 8), WidgetTiles.dragSpan(2, 2, 9f, 40f, l))
        assertEquals(TileSpan(1, 1), WidgetTiles.dragSpan(2, 2, -9f, -9f, l))
        assertEquals(TileSpan(2, 2), WidgetTiles.dragSpan(2, 2, 9f, 9f, SpanLimits(2, 2, 2, 2)))
        assertEquals(TileSpan(3, 3), WidgetTiles.dragSpan(2, 2, -9f, -9f, SpanLimits(3, 4, 3, 4)))
    }

    @Test
    fun onlyProvidersWithAConfigurationActivityCanBeReconfigured() {
        val plain = AppWidgetProviderInfo()
        assertFalse(WidgetTiles.canReconfigure(plain))
        val configurable = AppWidgetProviderInfo().apply { configure = ComponentName("com.example", "com.example.Config") }
        assertTrue(WidgetTiles.canReconfigure(configurable))
    }

    @Test
    fun transparencyChangesOnlyTheNamedWidget() {
        val other = TileSpec(2, TileSize.WIDE, TileContent.Widget(8, "com.example/.W", 0))
        val tiles = listOf(
            TileSpec(1, TileSize.WIDE, TileContent.Widget(7, "com.example/.W", 0)),
            other,
            TileSpec(3, TileSize.MEDIUM, TileContent.Weather),
        )
        val changed = WidgetTiles.withTransparency(tiles, 1, true)
        assertTrue((changed[0].content as TileContent.Widget).transparent)
        assertEquals(other, changed[1])
        assertEquals(tiles[2], changed[2])
        // Naming a tile that isn't a widget changes nothing.
        assertEquals(tiles, WidgetTiles.withTransparency(tiles, 3, true))
        assertEquals(tiles, WidgetTiles.withTransparency(changed, 1, false))
    }

    @Test
    fun widgetSearchMatchesAppAndWidgetNames() {
        assertTrue(matchesWidgetQuery("", "Clock", "Digital"))
        assertTrue(matchesWidgetQuery("dig", "Clock", "Digital"))
        assertTrue(matchesWidgetQuery("clock dig", "Clock", "Digital"))
        assertTrue(matchesWidgetQuery("  CLOCK ", "Clock", "Digital"))
        assertFalse(matchesWidgetQuery("analog", "Clock", "Digital"))
    }

    @Test
    fun theTransparentFlagRoundTripsAndOlderLayoutsStayOpaque() {
        val app = AppKey("com.example", "com.example.Main", 0)
        val tiles = listOf(
            TileSpec(1, TileSize.WIDE, TileContent.Widget(42, "com.example/.W", 10, transparent = true)),
            TileSpec(2, TileSize.WIDE, TileContent.Widget(43, "com.example/.W", 10)),
            TileSpec(3, TileSize.MEDIUM, TileContent.App(app)),
        )
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))

        val before = """[{"id":1,"size":"WIDE","type":"widget","widgetId":5,"provider":"a/.W","serial":0}]"""
        assertEquals(
            listOf(TileSpec(1, TileSize.WIDE, TileContent.Widget(5, "a/.W", 0, transparent = false))),
            TileJson.decode(JSONArray(before)),
        )
        // An opaque widget writes the same JSON as before the option existed.
        val opaque = TileJson.encode(listOf(tiles[1])).getJSONObject(0)
        assertFalse(opaque.has("transparent"))
    }
}
