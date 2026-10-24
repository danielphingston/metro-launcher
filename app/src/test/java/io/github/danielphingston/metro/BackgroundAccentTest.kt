package io.github.danielphingston.metro

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.danielphingston.metro.data.BackgroundAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAccentTest {
    private fun pixels(vararg blocks: Pair<Int, Int>): IntArray =
        blocks.flatMap { (color, count) -> List(count) { color } }.toIntArray()

    @Test
    fun greyAndDarkPhotosHaveNoAccent() {
        assertNull(BackgroundAccent.fromPixels(pixels(0xFF808080.toInt() to 100, 0xFFFFFFFF.toInt() to 50)))
        assertNull(BackgroundAccent.fromPixels(pixels(0xFF050A14.toInt() to 100)))
        assertNull(BackgroundAccent.fromPixels(IntArray(0)))
    }

    @Test
    fun transparentPixelsAreIgnored() {
        assertNull(BackgroundAccent.fromPixels(pixels(0x00FF0000 to 100)))
    }

    @Test
    fun picksTheMostSaturatedBucketOverTheBiggerDullOne() {
        // Plenty of washed-out brown against a smaller patch of vivid blue.
        val accent = BackgroundAccent.fromPixels(pixels(0xFF8C7A6B.toInt() to 300, 0xFF1060F0.toInt() to 120))
        assertNotNull(accent)
        val c = Color(accent!!)
        assertTrue("expected a blue accent, got $c", c.blue > c.red && c.blue > c.green)
    }

    @Test
    fun accentIsVividAndCarriesWhiteText() {
        for (source in listOf(0xFFFFE600.toInt(), 0xFF40FF40.toInt(), 0xFFA0C8FF.toInt(), 0xFFFF2020.toInt())) {
            val accent = Color(BackgroundAccent.fromPixels(pixels(source to 50))!!)
            assertEquals(1f, accent.alpha, 0f)
            assertTrue("too light for white text: $accent", accent.luminance() <= 0.36f)
        }
    }
}
