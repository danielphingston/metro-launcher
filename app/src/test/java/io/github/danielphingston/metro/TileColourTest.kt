package io.github.danielphingston.metro

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconColor
import io.github.danielphingston.metro.data.TileColourMode
import io.github.danielphingston.metro.data.TileColors
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.start.tileInk
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TileColourTest {
    private val app = TileContent.App(AppKey("a.b", "a.b.Main", 0))

    private fun pixels(vararg parts: Pair<Int, Int>) = parts.flatMap { (c, n) -> List(n) { c } }.toIntArray()

    @Test
    fun colourRoundTripsAndIsOmittedWhenDefault() {
        val tiles = listOf(
            TileSpec(1, TileSize.SMALL, app, 0, 0),
            TileSpec(2, TileSize.SMALL, app, 1, 0, color = "accent"),
            TileSpec(3, TileSize.SMALL, app, 2, 0, color = "icon"),
            TileSpec(4, TileSize.SMALL, app, 3, 0, color = "#E51400"),
            TileSpec(5, TileSize.MEDIUM, TileContent.Folder("f", listOf(TileSpec(6, TileSize.SMALL, app, color = "#0050EF"))), 0, 1, color = "icon"),
        )
        val json = TileJson.encode(tiles)
        assertTrue(!json.getJSONObject(0).has("color"))
        assertEquals(tiles, TileJson.decode(JSONArray(json.toString())))
    }

    @Test
    fun oldJsonAndGarbageDecodeToNoColour() {
        val old = """[{"id":1,"size":"SMALL","type":"app","app":"a.b/a.b.Main#0"}]"""
        assertNull(TileJson.decode(JSONArray(old)).single().color)
        for (bad in listOf("sparkles", "#12", "#GGGGGG", "red", "")) {
            val json = """[{"id":1,"size":"SMALL","type":"app","app":"a.b/a.b.Main#0","color":"$bad"}]"""
            assertNull(bad, TileJson.decode(JSONArray(json)).single().color)
        }
        val lower = """[{"id":1,"size":"SMALL","type":"app","app":"a.b/a.b.Main#0","color":"#e51400"}]"""
        assertEquals("#E51400", TileJson.decode(JSONArray(lower)).single().color)
    }

    @Test
    fun setReachesFolderChildren() {
        val child = TileSpec(6, TileSize.SMALL, app)
        val folder = TileSpec(5, TileSize.MEDIUM, TileContent.Folder("f", listOf(child)), 0, 0)
        val set = TileColors.set(listOf(folder), 6, "icon")
        assertEquals("icon", (set.single().content as TileContent.Folder).children.single().color)
        assertNull(TileColors.set(set, 6, "nonsense").let { (it.single().content as TileContent.Folder).children.single().color })
    }

    @Test
    fun resolutionPrefersTheTileThenTheMode() {
        val icon = 0xFF112233.toInt()
        // Default tiles follow the mode.
        assertNull(TileColors.resolve(null, TileColourMode.ACCENT, icon))
        assertEquals(icon, TileColors.resolve(null, TileColourMode.ICON, icon))
        // A tile's own choice beats the mode.
        assertNull(TileColors.resolve("accent", TileColourMode.ICON, icon))
        assertEquals(icon, TileColors.resolve("icon", TileColourMode.ACCENT, icon))
        assertEquals(0xFFE51400.toInt(), TileColors.resolve("#E51400", TileColourMode.ICON, icon))
        // No icon colour (grey icon, not loaded yet): the accent.
        assertNull(TileColors.resolve("icon", TileColourMode.ACCENT, null))
        assertNull(TileColors.resolve(null, TileColourMode.ICON, null))
        assertTrue(TileColors.usesIcon(null, TileColourMode.ICON))
        assertTrue(!TileColors.usesIcon("#E51400", TileColourMode.ICON))
    }

    @Test
    fun solidRedIconIsRed() {
        val red = IconColor.dominant(pixels(0xFFE02020.toInt() to 400))
        assertNotNull(red)
        val c = Color(red!!)
        assertTrue(c.red > 0.7f && c.green < 0.3f && c.blue < 0.3f)
    }

    @Test
    fun greyWhiteBlackAndTransparentIconsHaveNoColour() {
        assertNull(IconColor.dominant(pixels(0xFF808080.toInt() to 300, 0xFFFFFFFF.toInt() to 100)))
        assertNull(IconColor.dominant(pixels(0xFF000000.toInt() to 300, 0xFFFFFFFF.toInt() to 100)))
        assertNull(IconColor.dominant(pixels(0x00FF0000 to 500)))
        // A red dot on a transparent icon says too little.
        assertNull(IconColor.dominant(pixels(0xFFE02020.toInt() to 10, 0x00000000 to 566)))
    }

    @Test
    fun colourIgnoresGreyAndTransparentAroundIt() {
        val c = IconColor.dominant(pixels(0xFF1060E0.toInt() to 200, 0xFFFFFFFF.toInt() to 200, 0x00000000 to 100, 0xFF303030.toInt() to 76))
        assertNotNull(c)
        assertTrue(Color(c!!).blue > Color(c).red)
    }

    @Test
    fun vividnessRaisesSaturationMonotonically() {
        val muted = 0xFF8C7A7A.toInt() // dull rose
        var last = -1f
        for (v in 0..100 step 10) {
            val s = IconColor.hsv(Color(IconColor.adjust(muted, v))).second
            assertTrue("saturation at $v", s >= last - 0.001f)
            last = s
        }
        assertEquals(muted, IconColor.adjust(muted, 0))
        assertTrue(IconColor.hsv(Color(IconColor.adjust(muted, 100))).second > IconColor.hsv(Color(muted)).second + 0.3f)
        assertEquals(IconColor.adjust(muted, 100), IconColor.adjust(muted, 500))
    }

    @Test
    fun darkInkOnlyOnOpaqueLightTiles() {
        val yellow = Color(0xFFE3C800)
        assertEquals(Color.Black, tileInk(yellow, 1f))
        assertNull(tileInk(yellow, 0.6f))
        assertNull(tileInk(Color(0xFF0050EF), 1f))
        assertEquals(0xFF0050EF.toInt(), Color(0xFF0050EF).toArgb())
    }
}
