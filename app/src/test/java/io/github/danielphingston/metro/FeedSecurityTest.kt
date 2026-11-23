package io.github.danielphingston.metro

import android.graphics.Bitmap
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sun.net.httpserver.HttpServer
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.feed.CachedFeed
import io.github.danielphingston.metro.live.feed.Feed
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedFormatException
import io.github.danielphingston.metro.live.feed.FeedHttp
import io.github.danielphingston.metro.live.feed.FeedItem
import io.github.danielphingston.metro.live.feed.FeedParser
import io.github.danielphingston.metro.live.feed.FeedProblem
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.live.feed.Fetched
import io.github.danielphingston.metro.ui.tiles.FeedTiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * What a hostile feed, picture server or backup file could do, and that each is stopped without
 * changing what ordinary feeds, layouts and backups do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FeedSecurityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val originalHttp = FeedStore.http
    private val originalLimit = FeedStore.downloadLimitMs

    @After
    fun restore() {
        FeedStore.http = originalHttp
        FeedStore.downloadLimitMs = originalLimit
        FeedStore.clearMemory()
        java.io.File(context.cacheDir, "feed_images").deleteRecursively()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    private fun rss(title: String = "t", author: String = "a", description: String = "d") =
        "<rss><channel><item><title>${esc(title)}</title><author>${esc(author)}</author>" +
            "<description>${esc(description)}</description></item></channel></rss>"

    // ---- entities ----

    @Test
    fun aFeedDeclaringEntitiesIsRefusedBeforeExpandingThem() {
        // 700 bytes that expand to 300 million characters.
        val bomb = buildString {
            append("<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY a0 \"lollollollollollollollollollol\">")
            for (i in 1..9) append("<!ENTITY a$i \"" + "&a${i - 1};".repeat(10) + "\">")
            append("]><rss><channel><item><title>&a7;</title></item></channel></rss>")
        }
        val started = System.nanoTime()
        try {
            FeedParser.parse(bomb)
            fail("a feed declaring entities was read")
        } catch (e: FeedFormatException) {
            assertEquals("declares entities", e.message)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertEquals(FeedProblem.NOT_A_FEED, FeedStore.classify(FeedFormatException("declares entities")))
    }

    @Test
    fun aPlainDoctypeIsStillRead() {
        // RSS 0.91 names its DTD; nothing is declared, so the feed reads as before.
        val rss091 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
            <!-- a comment before the root -->
            <rss version="0.91"><channel><title>Old site</title>
            <item><title>Old headline &amp; more</title><link>https://example.com/1</link></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.parse(rss091)
        assertEquals("Old site", feed.title)
        assertEquals("Old headline & more", feed.items.single().title)
        // External entities were never fetched, and still aren't.
        val ext = """<?xml version="1.0"?><!DOCTYPE rss SYSTEM "https://example.com/x.dtd"><rss><channel><item><title>ab</title></item></channel></rss>"""
        assertEquals("ab", FeedParser.parse(ext).items.single().title)
    }

    // ---- text that used to take hours ----

    private fun assertQuick(label: String, block: () -> Unit) {
        val started = System.nanoTime()
        block()
        val ms = (System.nanoTime() - started) / 1_000_000
        // Each took hours at this size before; linear patterns take milliseconds.
        assertTrue("$label took $ms ms", ms < 5_000)
    }

    @Test
    fun craftedTextOfTwoMegabytesIsReadQuickly() {
        val n = 2_000_000
        assertQuick("digits") { FeedParser.parse(rss(description = "1".repeat(n))) }
        assertQuick("spaces after points") { FeedParser.parse(rss(description = "1 points |" + " ".repeat(n))) }
        assertQuick("points and open links") { FeedParser.parse(rss(description = "1 points | <a".repeat(n / 13))) }
        assertQuick("open tags in a title") { FeedParser.parse(rss(title = "<".repeat(n))) }
        assertQuick("at signs as author") { FeedParser.parse(rss(author = "@".repeat(n))) }
        assertQuick("open img tags") { FeedParser.parse(rss(description = "<img ".repeat(n / 5))) }
        assertQuick("long img tag") { FeedParser.parse(rss(description = "<img src=\"" + "a".repeat(n) + ">")) }
        assertQuick("open link tags") { FeedStore.discover("<link ".repeat(n / 6), "https://example.com/") }
        assertQuick("long attribute names") { FeedStore.discover("<link " + "a".repeat(4000) + ">".repeat(1) + "<link " + "b".repeat(n), "https://example.com/") }
    }

    @Test
    fun ordinaryTextReadsAsBefore() {
        // Lemmy's counts line, as served.
        assertEquals(479 to 94, FeedParser.counts("""submitted by <a href="https://lemm.ee/u/wfh">wfh</a><br>479 points | <a href="https://lemmy.ml/post/18268622">94 comments</a>"""))
        assertEquals(1 to 1, FeedParser.counts("1 point | 1 comment"))
        assertEquals(-3 to 0, FeedParser.counts("-3 points | 0 comments"))
        assertNull(FeedParser.counts("no counts here"))
        // Authors: "mail (Name)" shows the name, a profile link the user.
        val author = FeedParser.parse(rss(author = "news@example.com (Jane Doe)")).items.single().author
        assertEquals("Jane Doe", author)
        // Tags go, text stays.
        assertEquals("Hello world", FeedParser.clean("<b>Hello</b> <i>world</i>"))
        assertEquals("a & b", FeedParser.clean("a &amp;amp; b"))
        // A stray "<" no longer swallows the text up to the next tag.
        assertEquals("a < b c", FeedParser.clean("a &lt; b <i>c</i>"))
    }

    @Test
    fun theFirstPictureInTheTextIsFoundAsBefore() {
        assertEquals("https://example.com/a.jpg", FeedParser.firstImage("""<p>x</p><img src="https://example.com/a.jpg"><img src="https://example.com/b.jpg">"""))
        // The last src of the tag: lazy-loading pages put the real picture in data-src after a placeholder.
        assertEquals("https://example.com/real.jpg", FeedParser.firstImage("""<IMG src='https://example.com/blank.gif' data-src="https://example.com/real.jpg" alt="">"""))
        // A tag without a picture is passed over for the next one.
        assertEquals("https://example.com/c.jpg", FeedParser.firstImage("""<img alt="none"><img class="x" src="https://example.com/c.jpg" />"""))
        assertNull(FeedParser.firstImage("""<img srcset="https://example.com/s.jpg 2x">"""))
        assertNull(FeedParser.firstImage("<img src=\"https://example.com/never-closed.jpg\""))
        assertNull(FeedParser.firstImage("no pictures"))
    }

    // ---- long text ----

    @Test
    fun longTitlesAndAuthorsAreCut() {
        val item = FeedParser.parse(rss(title = "word ".repeat(400_000), author = "x".repeat(5_000))).items.single()
        assertEquals(FeedParser.MAX_TITLE, item.title.length)
        assertTrue(item.title.endsWith("…"))
        assertEquals(FeedParser.MAX_AUTHOR, item.author!!.length)
        // A title that fits is untouched.
        val lemmyLongest = "t".repeat(200)
        assertEquals(lemmyLongest, FeedParser.parse(rss(title = lemmyLongest)).items.single().title)
        // Never cut between the two halves of an emoji.
        val emoji = "a".repeat(FeedParser.MAX_TITLE - 2) + "😀😀😀"
        val cut = FeedParser.cap(emoji, FeedParser.MAX_TITLE)
        assertFalse(Character.isHighSurrogate(cut[cut.length - 2]))
    }

    @Test
    fun aCopySavedByAnEarlierVersionIsCutWhenRead() {
        val old = CachedFeed("https://example.com/feed", Feed("f".repeat(1_000), listOf(FeedItem("t".repeat(10_000), "https://example.com/1", author = "a".repeat(1_000)))), 5L)
        val read = FeedStore.decode(FeedStore.encode(old).toString())!!
        assertEquals(FeedParser.MAX_TITLE, read.feed!!.items.single().title.length)
        assertEquals(FeedParser.MAX_AUTHOR, read.feed!!.items.single().author!!.length)
        assertEquals(FeedParser.MAX_FEED_TITLE, read.feed!!.title!!.length)
        assertEquals("https://example.com/1", read.feed!!.items.single().link)
    }

    // ---- Lemmy pictures ----

    @Test
    fun lemmyPicturesComeOnlyFromTheInstanceOrThePostersOwn() {
        fun item(image: String?, home: String? = null) =
            FeedItem("t", "https://lemmy.world/post/1", image = image, authorHome = home, imageBytes = 10L)
        // Kept: as seen in live feeds (November 2026).
        val proxied = item("https://lemmy.ml/api/v3/image_proxy?url=https%3A%2F%2Flemmy.zip%2Fpictrs%2Fimage%2Fa.avif")
        assertEquals(proxied, FeedTiles.lemmyPicture(proxied, "lemmy.ml"))
        val local = item("https://lemmy.world/pictrs/image/9e21888a.webp")
        assertEquals(local, FeedTiles.lemmyPicture(local, "lemmy.world"))
        val posters = item("https://lemmy.zip/pictrs/image/c58ff2b6.avif", home = "lemmy.zip")
        assertEquals(posters, FeedTiles.lemmyPicture(posters, "lemmy.world"))
        val subdomain = item("https://media.piefed.social/posts/a.jpg", home = "piefed.social")
        assertEquals(subdomain, FeedTiles.lemmyPicture(subdomain, "lemmy.world"))
        assertEquals(item(null), FeedTiles.lemmyPicture(item(null), "lemmy.world"))
        // Dropped: any other server, chosen by whoever posted.
        for (other in listOf(
            item("https://raw.githubusercontent.com/someone/repo/main/a.png", home = "lemmy.sdf.org"),
            item("https://tracker.example/pixel.gif?id=42", home = "lemmy.world.example"),
            item("https://evil-lemmy.world/a.png"),
            item("https://lemmy.world.evil.example/a.png"),
            item("not a url"),
        )) {
            val shown = FeedTiles.lemmyPicture(other, "lemmy.world")
            assertNull(other.image, shown.image)
            assertNull(shown.imageBytes)
            assertEquals(other.title, shown.title)
            assertEquals(other.link, shown.link)
        }
    }

    // ---- picture files ----

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val body = (major + "\u0000\u0000\u0000\u0000" + compatible.joinToString("")).toByteArray(Charsets.ISO_8859_1)
        val size = 8 + body.size
        return bytes(0, 0, 0, size) + "ftyp".toByteArray() + body
    }

    @Test
    fun onlyWebPictureFormatsAreDecoded() {
        assertTrue(FeedStore.isWebImage(png()))
        assertTrue(FeedStore.isWebImage(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertTrue(FeedStore.isWebImage("GIF89a....".toByteArray()))
        assertTrue(FeedStore.isWebImage("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertTrue(FeedStore.isWebImage(ftyp("avif", "mif1", "miaf")))
        assertTrue(FeedStore.isWebImage(ftyp("mif1", "miaf", "avif")))
        assertTrue(FeedStore.isWebImage(ftyp("avis", "avis")))
        // Not decoded: HEIF, camera raw (TIFF-based DNG), BMP, ICO, web pages, nothing.
        assertFalse(FeedStore.isWebImage(ftyp("heic", "mif1", "heic")))
        assertFalse(FeedStore.isWebImage(bytes(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)))
        assertFalse(FeedStore.isWebImage("BM\u0000\u0000".toByteArray()))
        assertFalse(FeedStore.isWebImage(bytes(0, 0, 1, 0, 1, 0)))
        assertFalse(FeedStore.isWebImage("<!doctype html><html>".toByteArray()))
        assertFalse(FeedStore.isWebImage(ByteArray(0)))
    }

    @Test
    fun aFileThatIsNoWebPictureIsDroppedAndNotTriedAgain() {
        var asked = 0
        FeedStore.http = FeedHttp { url, _, _ ->
            asked++
            Fetched("<!doctype html><html>not found</html>".toByteArray(), url, "text/html")
        }
        assertNull(FeedStore.image(context, "https://example.com/a.jpg", 400, 400))
        assertNull(FeedStore.image(context, "https://example.com/a.jpg", 400, 400))
        assertEquals(1, asked)
        assertTrue(java.io.File(context.cacheDir, "feed_images").listFiles().orEmpty().none { !it.name.endsWith(".tmp") })
    }

    @Test
    fun ordinaryPicturesDecodeUncutAndOnlyExtremeOnesAreCut() {
        // Ordinary pictures: exactly the cover size as before, nothing cut.
        for ((w, h) in listOf(1600 to 1200, 1200 to 1600, 4000 to 1000, 400 to 400, 100 to 80, 3000 to 3000)) {
            val target = FeedStore.decodeTarget(w, h, 400, 400)
            val (cw, ch) = io.github.danielphingston.metro.live.ImageDecode.coverSize(w, h, 400, 400)
            assertEquals("$w x $h", cw to ch, target.width to target.height)
            assertNull("$w x $h", target.crop)
        }
        // A 65000x400 panorama would cover a 400x400 tile at full size (about 100 MB): its middle is kept.
        val panorama = FeedStore.decodeTarget(65_000, 400, 400, 400)
        val crop = panorama.crop!!
        assertEquals(1600, crop.width())
        assertEquals(400, crop.height())
        assertEquals((65_000 - 1600) / 2, crop.left)
        assertTrue(crop.right <= panorama.width && crop.bottom <= panorama.height && crop.left >= 0 && crop.top >= 0)
        // A small, very tall picture (never enlarged) is cut the same way.
        val tall = FeedStore.decodeTarget(100, 100_000, 400, 400).crop!!
        assertEquals(100, tall.width())
        assertEquals(1600, tall.height())
        // No crash on a zero-sized box or picture.
        assertNotNull(FeedStore.decodeTarget(0, 0, 0, 0))
    }

    // ---- downloads ----

    @Test
    fun aDownloadTricklingInIsStoppedAtTheTimeLimit() {
        var agent: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            agent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { out ->
                    repeat(600) {
                        out.write('<'.code)
                        out.flush()
                        Thread.sleep(100)
                    }
                }
            } catch (_: Exception) {
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        try {
            FeedStore.downloadLimitMs = 1_000
            val started = System.nanoTime()
            try {
                FeedStore.download("http://127.0.0.1:${server.address.port}/feed", 2 * 1024 * 1024, "*/*")
                fail("a trickling download finished")
            } catch (_: SocketTimeoutException) {
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            // Stopped near the limit (the server would have taken a minute).
            assertTrue("took $ms ms", ms < 10_000)
            assertEquals(FeedProblem.OFFLINE, FeedStore.classify(SocketTimeoutException()))
            // Who asked, without which version.
            assertEquals("android:io.github.danielphingston.metro (Metro launcher feed tile)", agent)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anOrdinaryDownloadStillWorks() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = rss(title = "Hello").toByteArray()
        server.createContext("/feed") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val fetched = FeedStore.download("http://127.0.0.1:${server.address.port}/feed", 2 * 1024 * 1024, "*/*")
            assertEquals("Hello", FeedParser.parse(fetched.bytes.toString(Charsets.UTF_8)).items.single().title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theUserAgentNamesNoVersion() {
        assertFalse(FeedStore.USER_AGENT.contains(BuildConfig.VERSION_NAME))
        assertTrue(FeedStore.USER_AGENT.startsWith("android:io.github.danielphingston.metro"))
    }

    // ---- credentials, layouts and backups ----

    @Test
    fun credentialsAreTakenOutOfAddressesAndNothingElse() {
        assertEquals("https://example.com/feed.xml", FeedAddress.withoutCredentials("https://me:secret@example.com/feed.xml"))
        assertEquals("https://example.com:8443/rss?token=abc#x", FeedAddress.withoutCredentials("https://me@example.com:8443/rss?token=abc#x"))
        assertEquals("https://example.com", FeedAddress.withoutCredentials("https://a:b@c@example.com"))
        // Other tokens stay: they can't be told from ordinary parameters.
        for (same in listOf(
            "https://example.com/feed?token=abc",
            "https://lemmy.ml/feeds/c/linux.xml?sort=Hot",
            "https://example.com/u/@someone/feed",
            "https://example.com/feed?mail=a@b.c",
            "HTTPS://Example.com/Feed",
            "not a url",
        )) assertEquals(same, FeedAddress.withoutCredentials(same))
        // Typed in the editor: dropped there too.
        assertEquals("https://example.com/feed", FeedAddress.normalize("me:secret@example.com/feed"))
        assertEquals(FeedAddress.Entry.Address("https://example.com/feed"), FeedAddress.parse("https://me:secret@example.com/feed"))
    }

    @Test
    fun aLayoutKeepsOnlyWebFeedAddresses() {
        // Everything the app saves passes unchanged.
        for (url in listOf(
            "https://lemmy.ml/feeds/c/linux.xml?sort=Hot",
            "https://www.theverge.com/rss/index.xml",
            "https://example.com:8443/feed?token=abc",
            "HTTPS://Example.com/Feed",
            "https://1.2.3.4/feed",
        )) assertEquals(url, FeedAddress.fromLayout(url))
        for (bad in listOf("intent://x#Intent;scheme=x;end", "file:///data/data/x", "javascript:alert(1)", "content://x/y", "https://", "")) {
            assertNull(bad, FeedAddress.fromLayout(bad))
        }
        val tiles = JSONArray(
            """[{"id":1,"size":"WIDE","type":"feed","urls":["intent://x#Intent;end","https://me:pw@example.com/feed"]},
                {"id":2,"size":"WIDE","type":"feed","urls":["javascript:alert(1)"]},
                {"id":3,"size":"WIDE","type":"feed","urls":["https://lemmy.ml/feeds/c/linux.xml?sort=Hot"]}]""",
        )
        val decoded = TileJson.decode(tiles)
        assertEquals(TileContent.Feed(listOf("https://example.com/feed")), decoded.single { it.id == 1L }.content)
        // A tile left with no address isn't loaded (kept as an unread entry, as before for an empty one).
        assertTrue(decoded.none { it.id == 2L })
        assertEquals(TileContent.Feed(listOf("https://lemmy.ml/feeds/c/linux.xml?sort=Hot")), decoded.single { it.id == 3L }.content)
    }

    @Test
    fun aTapNeverOpensAnythingButAWebAddress() {
        assertEquals("about:blank", FeedAddress.homePage("intent://x#Intent;scheme=x;end"))
        assertEquals("about:blank", FeedAddress.homePage("javascript:alert(1)"))
        assertEquals("about:blank", FeedAddress.homePage("file:///sdcard/a.html"))
        assertEquals("https://www.theverge.com/", FeedAddress.homePage("https://www.theverge.com/rss/index.xml"))
        assertEquals("https://lemmy.ml/c/linux", FeedAddress.homePage("https://lemmy.ml/feeds/c/linux.xml?sort=Hot"))
        val crafted = TileContent.Feed(listOf("intent://x#Intent;scheme=x;end"))
        assertEquals("about:blank", FeedTiles.intent(context, 991, crafted, null).data.toString())
    }

    @Test
    fun aBackupCarriesNoFeedCredentials() {
        val tiles = TileJson.encode(
            listOf(TileSpec(9, TileSize.WIDE, TileContent.Feed(listOf("https://example.com/plain", "https://me:secret@example.com/private")), 0, 0)),
        ).toString()
        val layouts = JSONObject()
            .put("active", 1)
            .put(
                "layouts",
                JSONArray().put(
                    JSONObject().put("id", 1).put("name", "work").put("tiles", JSONArray(tiles)).put("settings", JSONObject()),
                ),
            ).toString()
        val prefs = preferencesOf(
            stringPreferencesKey("tiles") to tiles,
            stringPreferencesKey("layouts") to layouts,
            stringPreferencesKey("tiles_backup") to tiles,
        )
        val exported = Backup.encode(prefs, "test", 0L)
        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("me:"))
        val restored = Backup.decode(exported)
        val feed = TileJson.read(restored[stringPreferencesKey("tiles")]!!)!!.tiles.single().content
        assertEquals(TileContent.Feed(listOf("https://example.com/plain", "https://example.com/private")), feed)
        assertTrue(restored[stringPreferencesKey("layouts")]!!.contains("example.com\\/private") || restored[stringPreferencesKey("layouts")]!!.contains("example.com/private"))
    }

    @Test
    fun aLayoutWithoutCredentialsIsExportedByteForByte() {
        val tiles = TileJson.encode(
            listOf(TileSpec(9, TileSize.WIDE, TileContent.Feed(listOf("https://example.com/u/@me/feed?mail=a@b.c")), 0, 0)),
        ).toString()
        assertEquals(tiles, Backup.withoutFeedCredentials(tiles))
        val noAt = """{"active":1,"layouts":[]}"""
        assertEquals(noAt, Backup.withoutFeedCredentials(noAt))
        assertEquals("not json @", Backup.withoutFeedCredentials("not json @"))
    }
}
