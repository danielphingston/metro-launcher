package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.ImageDecode
import io.github.danielphingston.metro.live.PhotoSource
import io.github.danielphingston.metro.ui.tiles.Mosaic
import io.github.danielphingston.metro.ui.tiles.MosaicGrid
import io.github.danielphingston.metro.ui.tiles.MosaicSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MosaicTest {
    @Test
    fun gridSizePerTileSize() {
        assertEquals(MosaicGrid(1, 1), Mosaic.grid(TileSize.SMALL))
        assertEquals(MosaicGrid(3, 3), Mosaic.grid(TileSize.MEDIUM))
        assertEquals(MosaicGrid(6, 3), Mosaic.grid(TileSize.WIDE))
        assertEquals(MosaicGrid(6, 6), Mosaic.grid(TileSize.LARGE))
        assertEquals(36, Mosaic.grid(TileSize.LARGE).cells)
    }

    @Test
    fun initialFillsCellsInContactOrder() {
        assertEquals(listOf(0, 1, 2, 3), Mosaic.initial(cells = 4, pool = 10))
        assertEquals(listOf(0, 1, -1, -1), Mosaic.initial(cells = 4, pool = 2))
    }

    @Test
    fun swapBringsInAContactNotOnShow() {
        val shown = Mosaic.initial(cells = 9, pool = 20)
        repeat(50) {
            val swap = Mosaic.nextSwap(shown, 20, lastCell = null, random = Random(it))!!
            assertTrue(swap.cell in 0 until 9)
            assertTrue(swap.contact in 9 until 20)
        }
    }

    @Test
    fun swapAvoidsTheCellJustSwapped() {
        val shown = Mosaic.initial(cells = 9, pool = 20)
        repeat(50) {
            assertNotEquals(4, Mosaic.nextSwap(shown, 20, lastCell = 4, random = Random(it))!!.cell)
        }
    }

    @Test
    fun swapNeedsASpareContact() {
        assertNull(Mosaic.nextSwap(Mosaic.initial(9, pool = 5), 5, null, Random(1)))
        assertNull(Mosaic.nextSwap(listOf(-1, -1), 0, null, Random(1)))
    }

    @Test
    fun singleCellMayBeSwappedAgain() {
        assertEquals(MosaicSwap(0, 1), Mosaic.nextSwap(listOf(0), 2, lastCell = 0, random = Random(1)))
    }
}

class PhotoSourceTest {
    @Test
    fun urisRoundTrip() {
        val uris = listOf("content://media/picker/0/a/1", "content://media/picker/0/a/2?x=1&y=2")
        assertEquals(uris, PhotoSource.decode(PhotoSource.encode(uris)))
    }

    @Test
    fun emptyAndMissingDecodeToNothing() {
        assertEquals(emptyList<String>(), PhotoSource.decode(null))
        assertEquals(emptyList<String>(), PhotoSource.decode(""))
        assertEquals("", PhotoSource.encode(emptyList()))
    }

    @Test
    fun blanksAndDuplicatesAreDropped() {
        assertEquals("a\nb", PhotoSource.encode(listOf("a", "", "b", "a", " ")))
        assertEquals(listOf("a", "b"), PhotoSource.decode("a\n\nb\na\n"))
    }

    @Test
    fun decodeCapsTheCount() {
        val many = (1..50).map { "content://p/$it" }
        assertEquals(PhotoSource.MAX_PHOTOS, PhotoSource.decode(many.joinToString("\n")).size)
    }
}

class ImageDecodeTest {
    @Test
    fun largeImageIsScaledToCoverTheBox() {
        // 4000x3000 into a 400x200 box: width is the limiting side.
        assertEquals(400 to 300, ImageDecode.coverSize(4000, 3000, 400, 200))
        // Portrait photo into a landscape box.
        assertEquals(400 to 533, ImageDecode.coverSize(3000, 4000, 400, 200))
    }

    @Test
    fun smallImageIsNeverUpscaled() {
        assertEquals(96 to 96, ImageDecode.coverSize(96, 96, 300, 300))
    }
}
