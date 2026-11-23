package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.danielphingston.metro.live.feed.FeedHttp
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.live.feed.Fetched
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Feed pictures on a real Android, with its real image decoder (which Robolectric can't run):
 * stored at tile size, decoded from that copy, very long pictures cut, and only web formats
 * decoded. No network: the downloads are handed over in memory.
 */
@RunWith(AndroidJUnit4::class)
class FeedPictureDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalHttp = FeedStore.http
    private val dir get() = File(context.cacheDir, "feed_images")

    @Before
    fun clean() {
        FeedStore.clearMemory()
        dir.deleteRecursively()
    }

    @After
    fun restore() {
        FeedStore.http = originalHttp
        FeedStore.clearMemory()
        dir.deleteRecursively()
    }

    private fun stored(): List<File> = dir.listFiles().orEmpty().filterNot { it.name.endsWith(".tmp") }

    /** Answers every download with [bytes]; returns the addresses asked for. */
    private fun serve(bytes: ByteArray): MutableList<String> {
        val asked = ArrayList<String>()
        FeedStore.http = FeedHttp { url, _, _ ->
            asked += url
            Fetched(bytes, url, "image/*")
        }
        return asked
    }

    /** A photo-like picture: smooth colour with noise, so it compresses like a real photo. */
    private fun photo(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 92): ByteArray {
        val random = Random(1)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = random.nextInt(48)
            val r = (x * 200 / w + n).coerceAtMost(255)
            val g = (y * 200 / h + n).coerceAtMost(255)
            val b = (128 + n).coerceAtMost(255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray().also { bitmap.recycle() }
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    private fun isWebp(bytes: ByteArray) =
        bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

    @Test
    fun aBigPhotoIsStoredAtTileSizeAndShownFromThatCopy() {
        val jpeg = photo(3000, 2000)
        assertTrue("the photo must be big enough to be shrunk: ${jpeg.size}", jpeg.size > FeedStore.KEEP_AS_IS_BYTES)
        val asked = serve(jpeg)
        val url = "https://example.com/photo.jpg"

        val shown = FeedStore.image(context, url, 400, 400)
        assertNotNull(shown)
        // Covers the tile, at about its size (3:2 covering 400x400 is 600x400).
        assertEquals(600, shown!!.width)
        assertEquals(400, shown.height)

        // Stored as a WebP at tile size, a fraction of the download.
        val copy = stored().single().readBytes()
        assertTrue(isWebp(copy))
        assertEquals(600 to 400, boundsOf(copy))
        assertTrue("stored ${copy.size} of ${jpeg.size} bytes", copy.size * 5 < jpeg.size)

        // Shown again, after a restart too, from the copy: no download.
        FeedStore.clearMemory()
        val again = FeedStore.image(context, url, 400, 400)
        assertEquals(600 to 400, again!!.width to again.height)
        assertEquals(1, asked.size)

        // A bigger tile gets its own, sharper copy.
        val wide = FeedStore.image(context, url, 800, 400)
        assertEquals(800 to 533, wide!!.width to wide.height)
        assertEquals(2, asked.size)
        assertEquals(2, stored().size)
    }

    @Test
    fun aSmallPictureIsKeptAsItCame() {
        val png = photo(300, 200, Bitmap.CompressFormat.PNG)
        assertTrue(png.size <= FeedStore.KEEP_AS_IS_BYTES)
        serve(png)
        assertNotNull(FeedStore.image(context, "https://example.com/small.png", 400, 400))
        assertArrayEquals(png, stored().single().readBytes())
    }

    @Test
    fun aPictureNoBiggerThanTheTileIsKeptAsItCame() {
        // Large in bytes, but no bigger than the tile: shrinking would only lose quality.
        val jpeg = photo(400, 400, quality = 100)
        assertTrue("the picture must be over the size kept as is: ${jpeg.size}", jpeg.size > FeedStore.KEEP_AS_IS_BYTES)
        serve(jpeg)
        assertNotNull(FeedStore.image(context, "https://example.com/exact.jpg", 400, 400))
        assertArrayEquals(jpeg, stored().single().readBytes())
    }

    @Test
    fun aVeryLongPictureIsCutToItsMiddle() {
        // 20000x200: covering a 400x400 tile at full size would be 16 MB; at most 4x the tile is kept.
        val wide = Bitmap.createBitmap(20_000, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        val png = ByteArrayOutputStream().also { wide.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        wide.recycle()
        serve(png)
        val shown = FeedStore.image(context, "https://example.com/panorama.png", 400, 400)
        assertNotNull(shown)
        assertEquals(1600, shown!!.width)
        assertEquals(200, shown.height)
    }

    @Test
    fun onlyWebPictureFormatsAreDecoded() {
        // WebP, as Lemmy's servers send them, decodes.
        val webp = photo(800, 600, Bitmap.CompressFormat.WEBP_LOSSY, 80)
        serve(webp)
        assertNotNull(FeedStore.image(context, "https://example.com/a.webp", 400, 400))
        // A BMP, which Android itself would decode, is refused: not a format feeds use.
        val bmp = bmp2x2()
        assertNotNull(ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bmp))))
        dir.deleteRecursively()
        serve(bmp)
        assertNull(FeedStore.image(context, "https://example.com/a.bmp", 400, 400))
        assertTrue(stored().isEmpty())
        // A web page served instead of a picture is refused too.
        serve("<!doctype html><html>gone</html>".toByteArray())
        assertNull(FeedStore.image(context, "https://example.com/gone.jpg", 400, 400))
    }

    /** A 2x2, 24-bit BMP. */
    private fun bmp2x2(): ByteArray {
        val row = byteArrayOf(0, 0, 0xFF.toByte(), 0, 0xFF.toByte(), 0, 0, 0) // two pixels, padded to 8 bytes
        val pixels = row + row
        val header = ByteBuffer.allocate(54).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte()); put('M'.code.toByte()); putInt(54 + pixels.size); putInt(0); putInt(54)
            putInt(40); putInt(2); putInt(2); putShort(1); putShort(24); putInt(0); putInt(pixels.size)
            putInt(2835); putInt(2835); putInt(0); putInt(0)
        }.array()
        return header + pixels
    }
}
