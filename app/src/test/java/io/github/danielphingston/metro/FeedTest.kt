package io.github.danielphingston.metro

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.feed.CachedFeed
import io.github.danielphingston.metro.live.feed.Feed
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedFormatException
import io.github.danielphingston.metro.live.feed.FeedItem
import io.github.danielphingston.metro.live.feed.FeedParser
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.ui.tiles.FeedTiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FeedTest {

    // ---- addresses ----

    @Test
    fun redditShorthandsBecomeTheirFeeds() {
        val android = "https://www.reddit.com/r/android/.rss"
        for (input in listOf("r/android", "/r/android", " r/android ", "reddit.com/r/android", "https://www.reddit.com/r/android/",
            "http://old.reddit.com/r/android", "https://www.reddit.com/r/android/.rss", "https://www.reddit.com/r/android.json")) {
            assertEquals(input, android, FeedAddress.normalize(input))
        }
        assertEquals("https://www.reddit.com/r/android/top/.rss", FeedAddress.normalize("r/android/top"))
        assertEquals("https://www.reddit.com/r/android/top/.rss?t=week", FeedAddress.normalize("https://www.reddit.com/r/android/top/?t=week"))
        assertEquals("https://www.reddit.com/user/spez/.rss", FeedAddress.normalize("u/spez"))
        assertEquals("https://www.reddit.com/r/pics+aww/.rss", FeedAddress.normalize("r/pics+aww"))
        assertEquals("https://www.reddit.com/.rss", FeedAddress.normalize("reddit.com"))
    }

    @Test
    fun otherAddressesAreKeptAndUpgradedToHttps() {
        assertEquals("https://example.com/feed.xml", FeedAddress.normalize("https://example.com/feed.xml"))
        assertEquals("https://example.com/feed.xml", FeedAddress.normalize("http://example.com/feed.xml"))
        assertEquals("https://blog.example.org/rss", FeedAddress.normalize("blog.example.org/rss"))
    }

    @Test
    fun nonsenseIsNoAddress() {
        for (input in listOf("", "  ", "android", "r/a", "ftp://example.com/feed", "two words.com", "javascript:alert(1)", "https://localhost/feed")) {
            assertNull(input, FeedAddress.normalize(input))
        }
    }

    @Test
    fun labelsHomePagesAndTheFieldText() {
        assertEquals("r/android", FeedAddress.label("https://www.reddit.com/r/android/top/.rss"))
        assertEquals("u/spez", FeedAddress.label("https://www.reddit.com/user/spez/.rss"))
        assertEquals("reddit", FeedAddress.label("https://www.reddit.com/.rss"))
        assertEquals("theverge.com", FeedAddress.label("https://www.theverge.com/rss/index.xml"))
        assertEquals("https://www.reddit.com/r/android/", FeedAddress.homePage("https://www.reddit.com/r/android/.rss"))
        assertEquals("https://www.theverge.com/", FeedAddress.homePage("https://www.theverge.com/rss/index.xml"))
        assertEquals("r/android/top", FeedAddress.shown("https://www.reddit.com/r/android/top/.rss"))
        assertEquals("https://www.theverge.com/rss/index.xml", FeedAddress.shown("https://www.theverge.com/rss/index.xml"))
        // What the field shows reads back as the same feed.
        for (url in listOf("https://www.reddit.com/r/android/top/.rss", "https://www.reddit.com/user/spez/.rss", "https://example.com/feed")) {
            assertEquals(url, FeedAddress.normalize(FeedAddress.shown(url)))
        }
    }

    // ---- parsing ----

    /** Trimmed from a real https://www.reddit.com/r/android/.rss response. */
    private val reddit = """
        <?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
        <category term="Android" label="r/Android"/><updated>2026-11-18T09:00:00+00:00</updated><icon>https://www.redditstatic.com/icon.png/</icon>
        <id>/r/android/.rss</id><link rel="self" href="https://www.reddit.com/r/android/.rss" type="application/atom+xml" />
        <link rel="alternate" href="https://www.reddit.com/r/android/" type="text/html" /><subtitle>Android news</subtitle><title>Android</title>
        <entry><author><name>/u/someone</name><uri>https://www.reddit.com/user/someone</uri></author><category term="Android" label="r/Android"/>
        <content type="html">&lt;table&gt; &lt;tr&gt;&lt;td&gt; &lt;a href=&quot;https://www.reddit.com/r/Android/comments/abc/x/&quot;&gt; &lt;img src=&quot;https://b.thumbs.redditmedia.com/pic.jpg?width=140&amp;amp;s=1&quot; alt=&quot;x&quot; /&gt; &lt;/a&gt;&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;</content>
        <id>t3_abc</id><media:thumbnail url="https://b.thumbs.redditmedia.com/thumb.jpg" />
        <link href="https://www.reddit.com/r/Android/comments/abc/pixel_11_review/" /><updated>2026-11-18T08:30:00+00:00</updated>
        <published>2026-11-18T08:00:00+00:00</published><title>Pixel 11 review: it&amp;#39;s &amp;quot;fine&amp;quot;</title></entry>
        <entry><author><name>/u/other</name><uri>https://www.reddit.com/user/other</uri></author>
        <content type="html">&lt;div class=&quot;md&quot;&gt;&lt;p&gt;Text post&lt;/p&gt;&lt;/div&gt;</content><id>t3_def</id>
        <link href="https://www.reddit.com/r/Android/comments/def/weekly_thread/" /><updated>2026-11-17T08:00:00+00:00</updated>
        <title>Weekly thread</title></entry>
        </feed>
    """.trimIndent()

    @Test
    fun readsRedditAtom() {
        val feed = FeedParser.parse(reddit)
        assertEquals("Android", feed.title)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("Pixel 11 review: it's \"fine\"", first.title)
        assertEquals("https://www.reddit.com/r/Android/comments/abc/pixel_11_review/", first.link)
        assertEquals("u/someone", first.author)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-18T08:00:00+00:00").toInstant().toEpochMilli(), first.publishedAt)
        assertEquals("https://b.thumbs.redditmedia.com/thumb.jpg", first.image)
        val second = feed.items[1]
        assertEquals("Weekly thread", second.title)
        assertEquals("u/other", second.author)
        assertNull(second.image)
        // Only updated: that stands in for published.
        assertEquals(java.time.OffsetDateTime.parse("2026-11-17T08:00:00+00:00").toInstant().toEpochMilli(), second.publishedAt)
    }

    @Test
    fun theHtmlPictureIsAFallback() {
        val feed = FeedParser.parse(reddit.replace("""<media:thumbnail url="https://b.thumbs.redditmedia.com/thumb.jpg" />""", ""))
        assertEquals("https://b.thumbs.redditmedia.com/pic.jpg?width=140&s=1", feed.items[0].image)
    }

    @Test
    fun readsRss2() {
        val rss = """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
            <channel><title>Example News</title><link>https://example.com/</link>
            <image><url>https://example.com/logo.png</url><title>Example News</title></image>
            <item><title><![CDATA[Big <b>news</b> &amp; more]]></title><link>https://example.com/a</link>
              <pubDate>Tue, 17 Nov 2026 10:00:00 GMT</pubDate><dc:creator>Jane Doe</dc:creator>
              <media:content url="https://example.com/a.jpg" medium="image"><media:title>Photo caption</media:title></media:content></item>
            <item><title>Second</title><guid isPermaLink="true">https://example.com/b</guid>
              <author>jo@example.com (Jo Bloggs)</author><enclosure url="https://example.com/b.png" type="image/png" length="1"/>
              <pubDate>Mon, 16 Nov 2026 10:00:00 EST</pubDate></item>
            <item><title>Third</title><guid isPermaLink="false">abc-123</guid>
              <description>&lt;p&gt;&lt;img src="https://example.com/c.jpg"&gt;&lt;/p&gt;</description></item>
            <item><link>https://example.com/untitled</link></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.parse(rss)
        assertEquals("Example News", feed.title)
        assertEquals(3, feed.items.size)
        assertEquals(FeedItem("Big news & more", "https://example.com/a", "Jane Doe", 1_794_909_600_000L, "https://example.com/a.jpg"), feed.items[0])
        assertEquals("https://example.com/b", feed.items[1].link)
        assertEquals("Jo Bloggs", feed.items[1].author)
        assertEquals("https://example.com/b.png", feed.items[1].image)
        assertEquals(1_794_909_600_000L - 24 * 3_600_000L + 5 * 3_600_000L, feed.items[1].publishedAt)
        assertNull(feed.items[2].link)
        assertEquals("https://example.com/c.jpg", feed.items[2].image)
    }

    @Test
    fun readsRss1() {
        val rdf = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <channel rdf:about="https://example.org/"><title>Old Site</title><link>https://example.org/</link></channel>
            <item rdf:about="https://example.org/1"><title>One</title><link>https://example.org/1</link><dc:date>2026-11-01T12:00:00Z</dc:date></item>
            </rdf:RDF>
        """.trimIndent()
        val feed = FeedParser.parse(rdf)
        assertEquals("Old Site", feed.title)
        assertEquals(listOf(FeedItem("One", "https://example.org/1", null, java.time.Instant.parse("2026-11-01T12:00:00Z").toEpochMilli())), feed.items)
    }

    @Test
    fun aPageIsNoFeed() {
        for (text in listOf("<html><body><h1>Hi</h1></body></html>", "not xml at all", "")) {
            try {
                FeedParser.parse(text)
                throw AssertionError("parsed: $text")
            } catch (_: FeedFormatException) {
            } catch (_: org.xmlpull.v1.XmlPullParserException) {
            }
        }
    }

    @Test
    fun atMostTwentyFiveItems() {
        val items = (1..40).joinToString("") { "<item><title>n$it</title></item>" }
        assertEquals(FeedParser.MAX_ITEMS, FeedParser.parse("<rss><channel><title>t</title>$items</channel></rss>").items.size)
    }

    // ---- the store and the tile ----

    @Test
    fun theSavedCopyReadsBack() {
        val cached = CachedFeed(
            "https://www.reddit.com/r/android/.rss",
            Feed("Android", listOf(FeedItem("a", "https://x/a", "u/me", 5L, "https://x/a.jpg"), FeedItem("b", null))),
            fetchedAt = 42,
        )
        assertEquals(cached, FeedStore.decode(FeedStore.encode(cached).toString()))
        assertNull(FeedStore.decode("{"))
    }

    @Test
    fun stalenessAndRetries() {
        val now = 10_000_000L
        assertTrue(FeedStore.isStale(null, now))
        val fresh = CachedFeed("u", Feed(null, emptyList()), fetchedAt = now - 60_000)
        assertFalse(FeedStore.isStale(fresh, now))
        assertTrue(FeedStore.isStale(fresh.copy(fetchedAt = now - FeedStore.MAX_AGE_MS - 1), now))
        // A failure waits a few minutes before the next try, with or without an older copy.
        val failed = CachedFeed("u", null, failedAt = now - 60_000, error = "no connection")
        assertFalse(FeedStore.isStale(failed, now))
        assertTrue(FeedStore.isStale(failed.copy(failedAt = now - FeedStore.RETRY_MS - 1), now))
    }

    @Test
    fun ticksCycleThroughTheHeadlines() {
        assertEquals(listOf(0, 1, 2, 0, 1), (0..4).map { FeedTiles.index(it, 3) })
        assertEquals(0, FeedTiles.index(7, 0))
        assertEquals("now", FeedTiles.age(1000, 1000))
        assertEquals("5m", FeedTiles.age(0, 5 * 60_000L))
        assertEquals("3h", FeedTiles.age(0, 3 * 3_600_000L))
        assertEquals("2d", FeedTiles.age(0, 50 * 3_600_000L))
    }

    @Test
    fun aTapOpensTheShownHeadlineElseTheFeedsPage() {
        val url = "https://www.reddit.com/r/android/.rss"
        assertEquals("https://www.reddit.com/r/android/", FeedTiles.intent(77, url).data.toString())
    }

    // ---- layout and backups ----

    @Test
    fun theTileIsSavedAsTypeFeedWithItsAddress() {
        val tiles = listOf(TileSpec(9, TileSize.WIDE, TileContent.Feed("https://www.reddit.com/r/android/.rss"), 0, 0))
        val json = TileJson.encode(tiles)
        assertEquals("feed", json.getJSONObject(0).getString("type"))
        assertEquals("https://www.reddit.com/r/android/.rss", json.getJSONObject(0).getString("url"))
        assertEquals(tiles, TileJson.decode(json))
        // A feed tile without an address can't be drawn: it is kept as an unread entry, not loaded.
        assertTrue(TileJson.decode(JSONArray("""[{"id":1,"size":"WIDE","type":"feed","url":""}]""")).isEmpty())
    }

    @Test
    fun aBackupWithAFeedTileRestoresIt() {
        val feed = TileContent.Feed("https://www.reddit.com/r/android/.rss")
        val tiles = TileJson.encode(listOf(TileSpec(9, TileSize.WIDE, feed, 0, 0))).toString()
        val prefs = preferencesOf(stringPreferencesKey("tiles") to tiles)
        val restored = Backup.decode(Backup.encode(prefs, "test", 0L))
        assertEquals(feed, TileJson.read(restored[stringPreferencesKey("tiles")]!!)!!.tiles.single().content)
    }
}
