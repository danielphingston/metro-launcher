package io.github.danielphingston.metro

import android.graphics.Bitmap
import io.github.danielphingston.metro.live.feed.FeedHttp
import io.github.danielphingston.metro.live.feed.FeedParser
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.live.feed.Fetched
import io.github.danielphingston.metro.live.feed.HttpStatusException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Headline pictures: WordPress sites are asked for a tile-sized picture, a picture the feed says is
 * too big (NASA's Image of the Day attaches 27 MB originals) is not downloaded, and a picture that
 * failed is not downloaded again each time its headline comes round.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FeedImageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val original = FeedStore.http
    private val originalDecode = FeedStore.decodeFile

    @After
    fun restore() {
        FeedStore.http = original
        FeedStore.decodeFile = originalDecode
        FeedStore.clearMemory()
        java.io.File(context.cacheDir, "feed_images").deleteRecursively()
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private val nasa = "https://www.nasa.gov/wp-content/uploads/2026/11/55658604092-c6f0765cc5-o.jpg"

    @Test
    fun wordPressPicturesAreAskedForAtTileSize() {
        assertEquals("$nasa?w=800", FeedStore.sizedImageUrl(nasa, 720))
        assertEquals("$nasa?w=200", FeedStore.sizedImageUrl(nasa, 90))
        assertEquals("$nasa?w=1600", FeedStore.sizedImageUrl(nasa, 3000))
        // Not WordPress, or already sized: as it is.
        assertEquals("https://ichef.bbci.co.uk/ace/standard/240/a.jpg", FeedStore.sizedImageUrl("https://ichef.bbci.co.uk/ace/standard/240/a.jpg", 720))
        assertEquals("$nasa?resize=768", FeedStore.sizedImageUrl("$nasa?resize=768", 720))
    }

    @Test
    fun aWordPressPictureDeclaredHugeIsDownloadedSmall() {
        val asked = ArrayList<String>()
        FeedStore.http = FeedHttp { url, _, _ ->
            asked += url
            Fetched(png(), url, "image/png")
        }
        // (Robolectric can't run ImageDecoder, so the decoded picture itself isn't checked here.)
        FeedStore.image(context, nasa, 720, 400, declaredBytes = 26_985_150)
        assertEquals(listOf("$nasa?w=800"), asked)
        assertEquals(1, java.io.File(context.cacheDir, "feed_images").listFiles().orEmpty().size)
        // Kept on disk: shown again, even after a restart, without another download.
        FeedStore.clearMemory()
        FeedStore.image(context, nasa, 720, 400, declaredBytes = 26_985_150)
        assertEquals(1, asked.size)
    }

    @Test
    fun lemmyPicturesAreAskedForAtTileSize() {
        val pictrs = "https://lemmy.world/pictrs/image/22e7080d-a117-4bff-91e8-d7df913a0d1b.jpeg"
        // Twice the tile's longer side, in steps of 200: a picture up to 2:1 still covers the tile.
        assertEquals("$pictrs?thumbnail=800&format=webp", FeedStore.sizedImageUrl(pictrs, 330, 330))
        assertEquals("$pictrs?thumbnail=1400&format=webp", FeedStore.sizedImageUrl(pictrs, 690, 330))
        assertEquals("$pictrs?thumbnail=400&format=webp", FeedStore.sizedImageUrl(pictrs, 90, 90))
        assertEquals("$pictrs?thumbnail=2000&format=webp", FeedStore.sizedImageUrl(pictrs, 1500, 1500))
        // lemmy.ml passes every picture through its proxy, which takes the same request.
        val proxy = "https://lemmy.ml/api/v3/image_proxy?url=https%3A%2F%2Flemmy.zip%2Fpictrs%2Fimage%2F0a55bb6c.avif"
        assertEquals("$proxy&thumbnail=800&format=webp", FeedStore.sizedImageUrl(proxy, 330, 330))
        // Already sized, or anything else: as it is.
        for (same in listOf(
            "$pictrs?thumbnail=256",
            "$proxy&thumbnail=256",
            "$pictrs#x",
            "http://lemmy.world/pictrs/image/a.jpeg",
            "https://lemmy.world/pictrs/image/a/b.jpeg",
            "https://media.piefed.social/posts/a.jpg",
            "https://raw.githubusercontent.com/a/b/main/pictrs/image.png",
        )) assertEquals(same, FeedStore.sizedImageUrl(same, 330, 330))
    }

    @Test
    fun aLemmyPictureComesSmallAndFallsBackToTheOriginal() {
        val pictrs = "https://lemmy.world/pictrs/image/4b23b8b3.jpeg"
        val small = "$pictrs?thumbnail=800&format=webp"
        // A server that can't make the smaller picture: the original, as before.
        val asked = ArrayList<String>()
        FeedStore.http = FeedHttp { url, _, _ ->
            asked += url
            if (url == small) throw HttpStatusException(400)
            Fetched(png(), url, "image/png")
        }
        FeedStore.image(context, pictrs, 330, 330)
        assertEquals(listOf(small, pictrs), asked)
        // The smaller one first: the original isn't asked for when that works.
        restore()
        asked.clear()
        FeedStore.http = FeedHttp { url, _, _ ->
            asked += url
            Fetched(png(), url, "image/png")
        }
        FeedStore.decodeFile = { _, w, h -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        assertNotNull(FeedStore.image(context, pictrs, 330, 330))
        assertEquals(listOf(small), asked)
        // Kept on disk under its own address: shown again without another download.
        FeedStore.clearMemory()
        assertNotNull(FeedStore.image(context, pictrs, 330, 330))
        assertEquals(listOf(small), asked)
        // Too big to fetch whole (declared): only the smaller picture is tried.
        restore()
        asked.clear()
        FeedStore.http = FeedHttp { url, _, _ ->
            asked += url
            throw HttpStatusException(400)
        }
        assertNull(FeedStore.image(context, pictrs, 330, 330, declaredBytes = 30_000_000))
        assertEquals(listOf(small), asked)
    }

    @Test
    fun aPictureDeclaredTooBigThatCantBeResizedIsNotDownloaded() {
        FeedStore.http = FeedHttp { _, _, _ -> throw AssertionError("no download expected") }
        assertNull(FeedStore.image(context, "https://example.com/huge.jpg", 720, 400, declaredBytes = 30_000_000))
    }

    @Test
    fun aFailedPictureIsNotTriedAgain() {
        var asked = 0
        FeedStore.http = FeedHttp { _, _, _ ->
            asked++
            throw HttpStatusException(404)
        }
        assertNull(FeedStore.image(context, "https://example.com/gone.jpg", 720, 400))
        assertNull(FeedStore.image(context, "https://example.com/gone.jpg", 720, 400))
        assertEquals(1, asked)
    }

    @Test
    fun theFeedsDeclaredPictureSizeIsRead() {
        // Trimmed from https://www.nasa.gov/feeds/iotd-feed/ (November 2026).
        val rss = """
            <rss version="2.0"><channel><title>NASA Image of the Day</title>
            <item><title>Empire State Building Goes Gold for Voyager</title>
            <link>https://www.nasa.gov/image-detail/nasas-voyager-1-spacecraft-light-day/</link>
            <enclosure url="$nasa" length="26985150" type="image/jpeg" />
            <pubDate>Wed, 18 Nov 2026 17:52 GMT</pubDate></item>
            <item><title>No length</title><enclosure url="https://example.com/a.jpg" type="image/jpeg" /></item>
            <item><title>Picture from the text</title><description>&lt;img src="https://example.com/b.jpg"&gt;</description></item>
            </channel></rss>
        """.trimIndent()
        val items = FeedParser.parse(rss).items
        assertEquals(nasa, items[0].image)
        assertEquals(26_985_150L, items[0].imageBytes)
        assertNotNull(items[0].publishedAt)
        assertNull(items[1].imageBytes)
        assertNull(items[2].imageBytes)
    }
}
