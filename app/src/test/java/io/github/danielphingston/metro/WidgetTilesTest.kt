package io.github.danielphingston.metro

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.util.SizeF
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
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
    ) = WidgetDims(w, h, resizeW, resizeH, cols, rows)

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

    @Test
    fun smallWidgetsGetSmallTilesAndBigOnesGetBigTiles() {
        // Typical declared sizes: 1x1 toggle, 2x2, 4x2, 4x4 and a tall 2x4.
        assertEquals(TileSize.SMALL, WidgetTiles.suggestedSize(dims(40f, 40f, cols = 1, rows = 1), cell))
        assertEquals(TileSize.MEDIUM, WidgetTiles.suggestedSize(dims(110f, 110f), cell))
        assertEquals(TileSize.WIDE, WidgetTiles.suggestedSize(dims(250f, 110f), cell))
        assertEquals(TileSize.LARGE, WidgetTiles.suggestedSize(dims(250f, 250f), cell))
        assertEquals(TileSize.LARGE, WidgetTiles.suggestedSize(dims(110f, 250f), cell))
        assertEquals(TileSize.LARGE, WidgetTiles.suggestedSize(dims(900f, 900f), cell))
    }

    @Test
    fun targetCellsWinOverTheMinimumSize() {
        // Declares 2x2 cells but only a tiny minimum size.
        assertEquals(TileSize.MEDIUM, WidgetTiles.suggestedSize(dims(40f, 40f, cols = 2, rows = 2), cell))
        // 4 columns by 2 rows.
        assertEquals(TileSize.WIDE, WidgetTiles.suggestedSize(dims(40f, 40f, cols = 4, rows = 2), cell))
    }

    @Test
    fun theTileNeverDropsBelowTheSizeTheWidgetCanShrinkTo() {
        // Small enough by default, but it can't be resized below 150dp wide.
        assertEquals(TileSize.SMALL, WidgetTiles.suggestedSize(dims(60f, 60f), cell))
        assertEquals(TileSize.MEDIUM, WidgetTiles.suggestedSize(dims(60f, 60f, resizeW = 150f, resizeH = 60f), cell))
        assertEquals(TileSize.WIDE, WidgetTiles.suggestedSize(dims(60f, 60f, resizeW = 300f, resizeH = 60f), cell))
    }

    @Test
    fun sixColumnsMakeSmallerTiles() {
        val narrow = WidgetTiles.cellDp(411, 6)
        // A 2x2 widget no longer fits a medium tile (about 128dp) of the six column grid.
        assertEquals(TileSize.MEDIUM, WidgetTiles.suggestedSize(dims(110f, 110f), narrow))
        assertEquals(TileSize.WIDE, WidgetTiles.suggestedSize(dims(150f, 110f), narrow))
    }

    @Test
    fun cellsAreEstimatedFromTheMinimumWhenNoTargetIsDeclared() {
        assertEquals(1, dims(40f, 40f).cols)
        assertEquals(2, dims(110f, 180f).cols)
        assertEquals(3, dims(110f, 180f).rows)
        assertEquals(4, dims(40f, 40f, cols = 4, rows = 1).cols)
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
