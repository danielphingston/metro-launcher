package io.github.danielphingston.metro

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import io.github.danielphingston.metro.data.CrashLog
import io.github.danielphingston.metro.ui.theme.MetroAccents
import io.github.danielphingston.metro.ui.theme.MetroColors
import io.github.danielphingston.metro.ui.theme.contrastRatio
import io.github.danielphingston.metro.ui.theme.photoSlice
import io.github.danielphingston.metro.ui.theme.readableOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yThemeTest {
    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.001f)
        assertEquals(contrastRatio(Color.White, Color.Red), contrastRatio(Color.Red, Color.White), 0.001f)
    }

    @Test
    fun brightAccentsGetBlackTextAndDarkOnesWhite() {
        for (name in listOf("lime", "yellow", "amber", "orange", "pink", "cyan")) {
            val accent = MetroAccents.first { it.first == name }.second
            assertEquals(name, Color.Black, readableOn(accent))
        }
        for (name in listOf("cobalt", "indigo", "crimson", "emerald", "brown", "red")) {
            val accent = MetroAccents.first { it.first == name }.second
            assertEquals(name, Color.White, readableOn(accent))
        }
    }

    @Test
    fun everyAccentHasAtLeastThreeToOneTextOnSolidSurfaces() {
        for ((name, accent) in MetroAccents) {
            val ink = MetroColors.of(dark = true, accent = accent).onSolidAccent
            assertTrue("$name ${contrastRatio(ink, accent)}", contrastRatio(ink, accent) >= 3f)
        }
    }

    @Test
    fun tileTextStaysWhite() {
        assertEquals(Color.White, MetroColors.of(true, Color(0xFFA4C400)).onAccent)
    }

    @Test
    fun shareTextKeepsTheNewestReportsWithinTheCap() {
        val reports = listOf("a".repeat(40), "b".repeat(40), "c".repeat(40))
        assertEquals(reports.joinToString(CrashLog.SEPARATOR), CrashLog.shareText(reports, 1000))
        val cap = 40 + CrashLog.SEPARATOR.length + 40
        assertEquals(reports.take(2).joinToString(CrashLog.SEPARATOR), CrashLog.shareText(reports, cap))
        assertEquals("a".repeat(10), CrashLog.shareText(reports, 10))
        assertEquals("", CrashLog.shareText(emptyList()))
    }

    @Test
    fun photoSliceCoversExactlyTheTileArea() {
        // 1:1 mapping: a 100x200 root with a 100x240 picture.
        val slice = photoSlice(100, 240, 100f, 200f, 0f, 10f, 30f, 20f, 40f)!!
        assertEquals(IntOffset(10, 30), slice.srcOffset)
        assertEquals(20, slice.srcSize.width)
        assertEquals(40, slice.srcSize.height)
        assertEquals(IntOffset(0, 0), slice.dstOffset)
        assertEquals(20, slice.dstSize.width)
        assertEquals(40, slice.dstSize.height)
    }

    @Test
    fun photoSliceFollowsTheParallaxOffsetAndScalesTheBitmap() {
        // The bitmap is half the size of the drawn picture: one source pixel covers two screen pixels.
        val slice = photoSlice(50, 120, 100f, 200f, 20f, 10f, 30f, 20f, 40f)!!
        assertEquals(IntOffset(5, 25), slice.srcOffset)
        assertEquals(10, slice.srcSize.width)
        assertEquals(20, slice.srcSize.height)
        assertEquals(IntOffset(0, 0), slice.dstOffset)
        assertEquals(20, slice.dstSize.width)
    }

    @Test
    fun photoSliceWidensToWholePixelsAndSkipsTilesOutsideThePicture() {
        val odd = photoSlice(50, 120, 100f, 200f, 0f, 11f, 31f, 20f, 40f)!!
        assertEquals(IntOffset(5, 15), odd.srcOffset)
        assertEquals(IntOffset(-1, -1), odd.dstOffset)
        assertNull(photoSlice(100, 240, 100f, 200f, 0f, 0f, 5000f, 20f, 40f))
    }
}
