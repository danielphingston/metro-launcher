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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FeedTest {

    // ---- addresses ----

    @Test
    fun lemmyCommunitiesFromHandlesAndLinks() {
        val linux = FeedAddress.Community("linux", "lemmy.ml")
        for (input in listOf("!linux@lemmy.ml", "linux@lemmy.ml", "c/linux@lemmy.ml", " !Linux@Lemmy.ML ", "lemmy.ml/c/linux",
            "https://lemmy.ml/c/linux/", "http://lemmy.ml/c/linux", "https://lemmy.ml/feeds/c/linux.xml?sort=New",
            // Seen through another instance: the feed comes from the community's own one.
            "https://lemmy.world/c/linux@lemmy.ml")) {
            assertEquals(input, FeedAddress.Entry.Lemmy(linux), FeedAddress.parse(input))
        }
        assertEquals("https://lemmy.ml/feeds/c/linux.xml?sort=Hot", FeedAddress.lemmyUrl(linux))
        assertEquals("https://lemmy.ml/feeds/c/linux.xml?sort=TopDay", FeedAddress.lemmyUrl(linux, FeedAddress.LemmySort.TOP_DAY))
        assertEquals(linux to FeedAddress.LemmySort.NEW, FeedAddress.lemmy("https://lemmy.ml/feeds/c/linux.xml?sort=New"))
        assertEquals(linux to FeedAddress.LemmySort.HOT, FeedAddress.lemmy("https://lemmy.ml/feeds/c/linux.xml"))
        assertEquals("https://lemmy.ml/feeds/c/linux.xml?sort=Active", FeedAddress.withSort("https://lemmy.ml/feeds/c/linux.xml?sort=Hot", FeedAddress.LemmySort.ACTIVE))
        assertEquals("https://example.com/feed", FeedAddress.withSort("https://example.com/feed", FeedAddress.LemmySort.NEW))
    }

    @Test
    fun otherAddressesAreFeedsUpgradedToHttps() {
        assertEquals(FeedAddress.Entry.Address("https://example.com/feed.xml"), FeedAddress.parse("https://example.com/feed.xml"))
        assertEquals(FeedAddress.Entry.Address("https://example.com/feed.xml"), FeedAddress.parse("http://example.com/feed.xml"))
        assertEquals(FeedAddress.Entry.Address("https://blog.example.org/rss"), FeedAddress.parse("blog.example.org/rss"))
        assertEquals(listOf("!linux@lemmy.ml", "example.com/feed", "c/x@y.org"), FeedAddress.split(" !linux@lemmy.ml, example.com/feed  c/x@y.org "))
    }

    @Test
    fun redditAndNonsenseAreRefusedWithAReason() {
        for (input in listOf("r/android", "https://www.reddit.com/r/android/.rss", "old.reddit.com/r/pics", "reddit.com")) {
            assertEquals(input, FeedAddress.Entry.Invalid(FeedAddress.REDDIT_ENDED), FeedAddress.parse(input))
        }
        assertEquals(FeedAddress.Entry.Invalid("add the community's instance, like linux@lemmy.world"), FeedAddress.parse("!linux"))
        assertEquals(FeedAddress.Entry.Invalid("add the community's instance, like linux@lemmy.world"), FeedAddress.parse("linux"))
        for (input in listOf("ftp://example.com/feed", "two words.com", "javascript:alert(1)", "https://localhost/feed", "linux@nowhere")) {
            assertTrue(input, FeedAddress.parse(input) is FeedAddress.Entry.Invalid)
        }
    }

    @Test
    fun labelsHomePagesAndKeys() {
        val linux = "https://lemmy.ml/feeds/c/linux.xml?sort=Hot"
        assertEquals("c/linux", FeedAddress.label(linux))
        assertEquals("theverge.com", FeedAddress.label("https://www.theverge.com/rss/index.xml"))
        assertEquals("c/linux +2", FeedAddress.label(listOf(linux, "https://a.example/f", "https://b.example/f")))
        assertEquals("!linux@lemmy.ml", FeedAddress.shown(linux))
        assertEquals("https://lemmy.ml/c/linux", FeedAddress.homePage(linux))
        assertEquals("https://www.theverge.com/", FeedAddress.homePage("https://www.theverge.com/rss/index.xml"))
        // The same feeds in any order are the same tile; a different sort is not.
        val tech = "https://lemmy.world/feeds/c/technology.xml?sort=Hot"
        assertTrue(FeedAddress.sameFeeds(listOf(linux, tech), listOf(tech, "https://LEMMY.ml/feeds/c/Linux.xml")))
        assertFalse(FeedAddress.sameFeeds(listOf(linux), listOf("https://lemmy.ml/feeds/c/linux.xml?sort=New")))
        assertFalse(FeedAddress.sameFeeds(listOf(linux), listOf(linux, tech)))
        assertTrue(FeedAddress.sameFeeds(listOf("https://www.Example.com/feed/"), listOf("https://example.com/feed")))
    }

    // ---- parsing ----

    /** Two items trimmed from a real https://lemmy.ml/feeds/c/linux.xml?sort=Hot response (November 2026). */
    private val lemmy = """
        <?xml version="1.0" encoding="utf-8"?><rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Lemmy - linux</title><link>https://lemmy.ml/c/linux</link><description>&lt;p&gt;Linux is a family of open source Unix-like operating systems.&lt;/p&gt;</description>
        <item><title>Canonical is Now Shipping AI-Written Security Patches to Ubuntu Pro Users</title><link>https://lemmy.ml/post/55448193</link><description><![CDATA[submitted by <a href="https://lemmy.ml/u/SocialistVibes01">SocialistVibes01</a> to <a href="https://lemmy.ml/c/linux">linux</a><br>94 points | <a href="https://lemmy.ml/post/55448193">23 comments</a><p>Body text.</p>]]></description><category domain="https://lemmy.ml/c/linux">Linux</category><comments>https://lemmy.ml/post/55448193</comments><enclosure url="https://itsfoss.com/news/canonical-surge-announcemengt/" length="0" type="text/html; charset=utf-8"/><guid>https://lemmy.ml/post/55448193</guid><pubDate>Wed, 18 Nov 2026 08:43:02 +0000</pubDate><media:content medium="image" url="https://lemmy.ml/api/v3/image_proxy?url=https%3A%2F%2Fitsfoss.com%2Fcontent%2Fimages%2F2026%2F11%2Fubuntu-surge.webp"></media:content><dc:creator>https://lemmy.ml/u/SocialistVibes01</dc:creator></item>
        <item><title>Understanding Linux and choosing your first Linux distro, v2.0</title><link>https://lemmy.ml/post/18268622</link><description><![CDATA[submitted by <a href="https://lemm.ee/u/wfh">wfh</a> to <a href="https://lemmy.ml/c/linux">linux</a><br>479 points | <a href="https://lemmy.ml/post/18268622">94 comments</a><p>Body text.</p>]]></description><category domain="https://lemmy.ml/c/linux">Linux</category><comments>https://lemmy.ml/post/18268622</comments><guid>https://lemmy.ml/post/18268622</guid><pubDate>Mon, 22 Jul 2024 09:09:18 +0000</pubDate><dc:creator>https://lemm.ee/u/wfh</dc:creator></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun readsALemmyCommunity() {
        val feed = FeedParser.parse(lemmy)
        assertEquals("Lemmy - linux", feed.title)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("Canonical is Now Shipping AI-Written Security Patches to Ubuntu Pro Users", first.title)
        assertEquals("https://lemmy.ml/post/55448193", first.link)
        assertEquals("SocialistVibes01", first.author)
        assertEquals(94, first.points)
        // The author's instance, from their profile link: here the feed's own.
        assertEquals("lemmy.ml", first.authorHome)
        assertEquals(23, first.comments)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-18T08:43:02Z").toInstant().toEpochMilli(), first.publishedAt)
        // The picture comes through the instance's image proxy; the linked article (an HTML enclosure) is no picture.
        assertEquals("https://lemmy.ml/api/v3/image_proxy?url=https%3A%2F%2Fitsfoss.com%2Fcontent%2Fimages%2F2026%2F11%2Fubuntu-surge.webp", first.image)
        val second = feed.items[1]
        assertEquals("wfh", second.author)
        // Written on lemm.ee and listed by lemmy.ml: its link is lemmy.ml's copy.
        assertEquals("lemm.ee", second.authorHome)
        assertEquals(479, second.points)
        assertEquals(94, second.comments)
        assertNull(second.image)
        assertEquals("94 points · 23 comments · 42m", FeedTiles.byline(first, first.publishedAt!! + 42 * 60_000L))
        assertEquals("u · 1h", FeedTiles.byline(FeedItem("t", null, "u", 0L), 3_600_000L))
    }

    /** An Atom feed with a person as author, a thumbnail and HTML content. */
    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
        <updated>2026-11-18T09:00:00+00:00</updated><id>urn:x</id><link rel="self" href="https://example.com/atom.xml" />
        <link rel="alternate" href="https://example.com/" type="text/html" /><title>Example</title>
        <entry><author><name>/u/someone</name><uri>https://example.com/user/someone</uri></author>
        <content type="html">&lt;table&gt; &lt;tr&gt;&lt;td&gt; &lt;img src=&quot;https://example.com/pic.jpg?width=140&amp;amp;s=1&quot; alt=&quot;x&quot; /&gt; &lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;</content>
        <id>t3_abc</id><media:thumbnail url="https://example.com/thumb.jpg" />
        <link href="https://example.com/posts/abc/" /><updated>2026-11-18T08:30:00+00:00</updated>
        <published>2026-11-18T08:00:00+00:00</published><title>Review: it&amp;#39;s &amp;quot;fine&amp;quot;</title></entry>
        <entry><author><name>other</name></author><id>t3_def</id>
        <link href="https://example.com/posts/def/" /><updated>2026-11-17T08:00:00+00:00</updated><title>Weekly thread</title></entry>
        </feed>
    """.trimIndent()

    @Test
    fun readsAtom() {
        val feed = FeedParser.parse(atom)
        assertEquals("Example", feed.title)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("Review: it's \"fine\"", first.title)
        assertEquals("https://example.com/posts/abc/", first.link)
        assertEquals("u/someone", first.author)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-18T08:00:00+00:00").toInstant().toEpochMilli(), first.publishedAt)
        assertEquals("https://example.com/thumb.jpg", first.image)
        assertNull(first.points)
        // Only updated: that stands in for published.
        assertEquals(java.time.OffsetDateTime.parse("2026-11-17T08:00:00+00:00").toInstant().toEpochMilli(), feed.items[1].publishedAt)
    }

    @Test
    fun theHtmlPictureIsAFallback() {
        val feed = FeedParser.parse(atom.replace("""<media:thumbnail url="https://example.com/thumb.jpg" />""", ""))
        assertEquals("https://example.com/pic.jpg?width=140&s=1", feed.items[0].image)
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
    fun onlyWebLinksAreKept() {
        val rss = "<rss><channel><title>t</title>" +
            "<item><title>a</title><link>intent://x#Intent;scheme=x;end</link></item>" +
            "<item><title>b</title><link>javascript:alert(1)</link></item>" +
            "<item><title>c</title><link> https://example.com/c </link></item></channel></rss>"
        assertEquals(listOf(null, null, "https://example.com/c"), FeedParser.parse(rss).items.map { it.link })
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
            "https://lemmy.ml/feeds/c/linux.xml?sort=Hot",
            Feed("Lemmy - linux", listOf(FeedItem("a", "https://x/a", "me", 5L, "https://x/a.jpg", 94, 23, "lemmy.ml"), FeedItem("b", null))),
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
    fun feedsAreTakenInTurn() {
        fun item(t: String) = FeedItem(t, "https://x/$t")
        val a = listOf(item("a1"), item("a2"), item("a3"))
        val b = listOf(item("b1"), item("shared"))
        val c = listOf(item("c1"), item("shared"))
        assertEquals(listOf("a1", "b1", "c1", "a2", "shared", "a3"), FeedTiles.merge(listOf(a, b, c)).map { it.title })
        assertEquals(emptyList<FeedItem>(), FeedTiles.merge(emptyList()))
        // With nothing to show, a feed that loaded (just empty) says more than a failure, a failure more than loading.
        val failed = CachedFeed("u1", null, failedAt = 1, error = "no connection")
        val empty = CachedFeed("u2", Feed(null, emptyList()), fetchedAt = 1)
        assertEquals(empty, FeedTiles.summary(listOf(null, failed, empty)))
        assertEquals(failed, FeedTiles.summary(listOf(null, failed)))
        assertNull(FeedTiles.summary(listOf(null, null)))
    }

    @Test
    fun aHeadlineWaitsForItsPicture() {
        val pictured = FeedItem("p", "https://x/p", image = "https://x/p.jpg")
        // No picture, or nothing next: move on.
        assertTrue(FeedTiles.readyToShow(FeedItem("t", "https://x/t"), emptySet()))
        assertTrue(FeedTiles.readyToShow(null, emptySet()))
        // A picture still on its way: stay for this turn.
        assertFalse(FeedTiles.readyToShow(pictured, emptySet()))
        // Loaded, or found missing (both are in the loaded set): move on.
        assertTrue(FeedTiles.readyToShow(pictured, setOf("https://x/p.jpg")))
    }

    @Test
    fun pinnedPostsAreLeftOut() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        fun item(t: String, age: Long) = FeedItem(t, "https://x/$t", publishedAt = now - age)
        // lemmy.world's !pics on 19 November 2026: a rules post from March leads this morning's photos.
        val pics = listOf(item("Memes and Violent Posts", 255 * day), item("Tasty snack", 0), item("Sleepy time", day / 2))
        assertEquals(listOf("Tasty snack", "Sleepy time"), FeedTiles.withoutPinned(pics).map { it.title })
        // Two pinned (community and instance) both go.
        val two = listOf(item("Instance notice", 40 * day), item("Rules", 300 * day), item("Fresh", 0))
        assertEquals(listOf("Fresh"), FeedTiles.withoutPinned(two).map { it.title })
        // A quiet community, everything old: kept. An old post further down: kept.
        val quiet = listOf(item("a", 90 * day), item("b", 95 * day), item("c", 120 * day))
        assertEquals(quiet, FeedTiles.withoutPinned(quiet))
        val olderBelow = listOf(item("new", 0), item("old", 30 * day))
        assertEquals(olderBelow, FeedTiles.withoutPinned(olderBelow))
        // No dates, nothing to judge by: kept.
        val undated = listOf(FeedItem("x", null), FeedItem("y", null))
        assertEquals(undated, FeedTiles.withoutPinned(undated))
    }

    @Test
    fun aTapOpensTheShownHeadlineElseTheFeedsPage() {
        val feed = TileContent.Feed(listOf("https://lemmy.ml/feeds/c/linux.xml?sort=Hot"))
        val context = RuntimeEnvironment.getApplication()
        assertEquals("https://lemmy.ml/c/linux", FeedTiles.intent(context, 77, feed, null).data.toString())
        // A Lemmy app that can't take the link (here: not installed) is passed over for the system's choice.
        assertNull(FeedTiles.intent(context, 77, feed, "com.example.gone").`package`)
        assertTrue(feed.isLemmy)
        assertFalse(TileContent.Feed(listOf("https://example.com/feed")).isLemmy)
        assertFalse(TileContent.Feed(listOf("https://lemmy.ml/feeds/c/linux.xml", "https://example.com/feed")).isLemmy)
    }

    // ---- layout and backups ----

    private val twoFeeds = TileContent.Feed(listOf("https://lemmy.ml/feeds/c/linux.xml?sort=New", "https://lemmy.world/feeds/c/technology.xml?sort=New"))

    @Test
    fun theTileIsSavedAsTypeFeedWithItsAddresses() {
        val tiles = listOf(TileSpec(9, TileSize.WIDE, twoFeeds, 0, 0))
        val json = TileJson.encode(tiles)
        assertEquals("feed", json.getJSONObject(0).getString("type"))
        assertEquals(2, json.getJSONObject(0).getJSONArray("urls").length())
        assertEquals(tiles, TileJson.decode(json))
        val one = TileContent.Feed(listOf("https://example.com/feed.xml"))
        assertEquals(one, TileJson.decode(TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, one)))).single().content)
        // A feed tile without addresses can't be drawn: it is kept as an unread entry, not loaded.
        assertTrue(TileJson.decode(JSONArray("""[{"id":1,"size":"WIDE","type":"feed","urls":[]}]""")).isEmpty())
        assertTrue(TileJson.decode(JSONArray("""[{"id":1,"size":"WIDE","type":"feed"}]""")).isEmpty())
    }

    @Test
    fun aBackupWithAFeedTileRestoresIt() {
        val tiles = TileJson.encode(listOf(TileSpec(9, TileSize.WIDE, twoFeeds, 0, 0))).toString()
        val prefs = preferencesOf(stringPreferencesKey("tiles") to tiles)
        val restored = Backup.decode(Backup.encode(prefs, "test", 0L))
        assertEquals(twoFeeds, TileJson.read(restored[stringPreferencesKey("tiles")]!!)!!.tiles.single().content)
    }
}
