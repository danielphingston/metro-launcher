package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedAddress.Community
import io.github.danielphingston.metro.live.feed.FeedCheck
import io.github.danielphingston.metro.live.feed.FeedHttp
import io.github.danielphingston.metro.live.feed.FeedProblem
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.live.feed.Fetched
import io.github.danielphingston.metro.live.feed.HttpStatusException
import io.github.danielphingston.metro.live.feed.TooLargeException
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.FeedEditor
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/** The checks a new feed goes through, and the editor that uses them, on a fake network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FeedEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private val original = FeedStore.http
    private val originalFederation = FeedStore.federation

    @After
    fun restore() {
        FeedStore.http = original
        FeedStore.federation = originalFederation
        FeedStore.clearMemory()
    }

    private fun rss(vararg titles: String) =
        "<rss><channel><title>t</title>" + titles.joinToString("") { "<item><title>$it</title><link>https://example.com/$it</link></item>" } + "</channel></rss>"

    /** Answers by address: a body, (body to content type), or an exception to throw. Anything else is a 404. */
    private fun network(vararg routes: Pair<String, Any>) {
        val map = routes.toMap()
        FeedStore.http = FeedHttp { url, _, _ ->
            when (val answer = map[url] ?: throw HttpStatusException(404)) {
                is Exception -> throw answer
                is Pair<*, *> -> Fetched((answer.first as String).toByteArray(), url, answer.second as String)
                else -> Fetched((answer as String).toByteArray(), url, "application/rss+xml")
            }
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private fun community(name: String, instance: String = "lemmy.ml") = FeedAddress.lemmyUrl(Community(name, instance))

    // ---- checking a feed ----

    @Test
    fun eachProblemIsNamed() = runBlocking {
        network(
            "https://ok.example/feed" to rss("one"),
            "https://empty.example/feed" to rss(),
            "https://refused.example/feed" to HttpStatusException(403),
            "https://busy.example/feed" to HttpStatusException(429),
            "https://broken.example/feed" to HttpStatusException(503),
            "https://lemmy.ml/feeds/c/nope.xml?sort=Hot" to HttpStatusException(400),
            "https://offline.example/feed" to IOException("unreachable"),
            "https://tls.example/feed" to SSLHandshakeException("bad cert"),
            "https://big.example/feed" to TooLargeException(),
            "https://json.example/feed" to "{\"not\": \"xml\"}",
            "https://page.example/" to ("<!doctype html><html><head><title>Hi</title></head></html>" to "text/html"),
        )
        assertTrue(FeedStore.check(context, "https://ok.example/feed") is FeedCheck.Ok)
        fun failed(url: String) = (runBlocking { FeedStore.check(context, url) } as FeedCheck.Failed)
        assertEquals("nothing at that address (404)", failed("https://missing.example/feed").message)
        assertEquals(FeedProblem.EMPTY, failed("https://empty.example/feed").problem)
        assertEquals(FeedProblem.REFUSED, failed("https://refused.example/feed").problem)
        assertEquals(FeedProblem.BUSY, failed("https://busy.example/feed").problem)
        assertEquals("the site has a problem (503)", failed("https://broken.example/feed").message)
        // Lemmy says 400 "CouldntFindCommunity" for a community it doesn't host.
        assertEquals(FeedProblem.NOT_FOUND, failed("https://lemmy.ml/feeds/c/nope.xml?sort=Hot").problem)
        assertEquals(FeedProblem.OFFLINE, failed("https://offline.example/feed").problem)
        assertEquals(FeedProblem.INSECURE, failed("https://tls.example/feed").problem)
        assertEquals(FeedProblem.TOO_LARGE, failed("https://big.example/feed").problem)
        assertEquals(FeedProblem.NOT_A_FEED, failed("https://json.example/feed").problem)
        assertEquals(FeedProblem.WEB_PAGE, failed("https://page.example/").problem)
        // Only problems that may pass offer "add anyway".
        assertEquals(
            setOf(FeedProblem.BUSY, FeedProblem.SERVER, FeedProblem.OFFLINE, FeedProblem.EMPTY),
            FeedProblem.entries.filter { it.retryable }.toSet(),
        )
    }

    @Test
    fun withoutAFederationAnswerThereIsNoAddress() = runBlocking {
        // A server that doesn't answer the federation way: nothing else is asked, the tap opens the browser.
        var asked = 0
        FeedStore.federation = {
            asked++
            FeedStore.Federated.Unknown
        }
        FeedStore.http = FeedHttp { _, _, _ -> throw AssertionError("no API call expected") }
        assertNull(FeedStore.canonicalPostLink("https://programming.dev/post/59546847"))
        assertEquals(1, asked)
        // Not a Lemmy post link: nothing is asked.
        assertNull(FeedStore.canonicalPostLink("https://example.com/article"))
        assertEquals(1, asked)
    }

    @Test
    fun theFederationAnswerComesFirstAndLocalAuthorsNeedNone() = runBlocking {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        FeedStore.federation = { link ->
            asked.incrementAndGet()
            when (link) {
                "https://programming.dev/post/59546847" -> FeedStore.Federated.Moved("https://lemmy.today/post/63466711")
                "https://programming.dev/post/1" -> FeedStore.Federated.Here
                else -> FeedStore.Federated.Unknown
            }
        }
        // The API is never needed here.
        FeedStore.http = FeedHttp { _, _, _ -> throw AssertionError("no API call expected") }
        assertEquals("https://lemmy.today/post/63466711", FeedStore.canonicalPostLink("https://programming.dev/post/59546847", "lemmy.today"))
        assertEquals("https://programming.dev/post/1", FeedStore.canonicalPostLink("https://programming.dev/post/1"))
        assertEquals(2, asked.get())
        // An author of the feed's own instance: the link is the address, nothing is asked.
        assertEquals("https://programming.dev/post/7", FeedStore.canonicalPostLink("https://programming.dev/post/7", "programming.dev"))
        assertTrue(FeedStore.knowsCanonical("https://programming.dev/post/7"))
        assertEquals(2, asked.get())
    }

    @Test
    fun aPrefetchAndATapShareOneLookup() = runBlocking {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        FeedStore.federation = {
            asked.incrementAndGet()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            FeedStore.Federated.Moved("https://lemmy.today/post/9")
        }
        val link = "https://programming.dev/post/9"
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val prefetch = scope.async { FeedStore.canonicalPostLink(link) }
        // Let the prefetch start before the tap asks.
        while (asked.get() == 0) kotlinx.coroutines.delay(5)
        val tap = scope.async { FeedStore.canonicalPostLink(link) }
        kotlinx.coroutines.delay(50)
        release.countDown()
        assertEquals("https://lemmy.today/post/9", prefetch.await())
        assertEquals("https://lemmy.today/post/9", tap.await())
        assertEquals(1, asked.get())
    }

    @Test
    fun theFederationLookupAsksForHeadersOnlyAndReadsTheRedirect() {
        // A tiny local server standing in for a Lemmy instance.
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += "${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Accept")}"
            when (exchange.requestURI.path) {
                "/post/59546847" -> {
                    exchange.responseHeaders.add("Location", "https://lemmy.today/post/63466711")
                    exchange.sendResponseHeaders(308, -1)
                }
                "/post/1" -> exchange.sendResponseHeaders(200, -1)
                else -> exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(FeedStore.Federated.Moved("https://lemmy.today/post/63466711"), FeedStore.federation("$base/post/59546847"))
            assertEquals(FeedStore.Federated.Here, FeedStore.federation("$base/post/1"))
            assertEquals(FeedStore.Federated.Unknown, FeedStore.federation("$base/post/404"))
            // Headers only, asking for the ActivityPub object; the redirect is read, not followed.
            assertEquals(
                listOf(
                    "HEAD /post/59546847 application/activity+json",
                    "HEAD /post/1 application/activity+json",
                    "HEAD /post/404 application/activity+json",
                ),
                seen.toList(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun communityProblemsNameTheCommunity() {
        val linux = Community("linux", "lemmy.world")
        assertEquals("!linux@lemmy.world wasn't found on lemmy.world", FeedProblem.NOT_FOUND.communityMessage(linux))
        assertEquals("couldn't reach lemmy.world, check your connection", FeedProblem.OFFLINE.communityMessage(linux))
        assertEquals("lemmy.world doesn't look like a Lemmy instance", FeedProblem.WEB_PAGE.communityMessage(linux))
        assertEquals("!linux@lemmy.world has no posts", FeedProblem.EMPTY.communityMessage(linux))
    }

    @Test
    fun aPageLeadsToTheFeedItNames() = runBlocking {
        val page = """<html><head>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/rss+xml" title="Posts" href="/blog/feed.xml?x=1&amp;y=2">
            </head><body>hi</body></html>"""
        network(
            "https://blog.example/" to (page to "text/html; charset=utf-8"),
            "https://blog.example/blog/feed.xml?x=1&y=2" to rss("first post"),
        )
        val result = FeedStore.check(context, "https://blog.example/") as FeedCheck.Ok
        assertEquals("https://blog.example/blog/feed.xml?x=1&y=2", result.url)
        // The good copy is kept, so the new tile shows it straight away.
        assertEquals("first post", FeedStore.feeds.value[result.url]?.feed?.items?.single()?.title)
        assertEquals("https://x.example/f", FeedStore.discover("""<link href="http://x.example/f" type="application/atom+xml" rel="alternate">""", "https://x.example/"))
        assertNull(FeedStore.discover("""<link rel="alternate" hreflang="de" href="/de/">""", "https://x.example/"))
    }

    // ---- the editor ----

    private fun editor(initial: TileContent.Feed? = null, taken: List<List<String>> = emptyList(), onDone: (TileContent.Feed) -> Unit) {
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    FeedEditor(initial, lemmyApp = null, isTaken = { urls -> taken.any { FeedAddress.sameFeeds(it, urls) } }, onDone = onDone, onCancel = {})
                }
            }
        }
    }

    private fun field() = rule.onNode(hasSetTextAction())

    /** Taps [text], scrolling the editor to it first: the test window is shorter than a phone. */
    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun communitiesAreCheckedAsTheyAreAdded() {
        network(community("linux") to rss("a"), community("technology", "lemmy.world") to rss("b"))
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("!linux@lemmy.ml lemmy.world/c/technology !nope@lemmy.ml")
        rule.onNodeWithText("add").performClick()
        waitFor("!nope@lemmy.ml wasn't found on lemmy.ml")
        rule.onNodeWithText("!linux@lemmy.ml").assertExists()
        rule.onNodeWithText("!technology@lemmy.world").assertExists()
        rule.onNodeWithText("add anyway").assertDoesNotExist()
        // The entry that failed stays in the field to fix, and "add tile" tries it again rather than dropping it.
        rule.onNodeWithText("!nope@lemmy.ml").assertExists()
        tap("add tile")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("checking !nope@lemmy.ml…").fetchSemanticsNodes().isEmpty() }
        assertNull(saved)
        // Removing one takes it off the list.
        rule.onAllNodesWithText("remove")[0].performClick()
        rule.onNodeWithText("!linux@lemmy.ml").assertDoesNotExist()
    }

    @Test
    fun aLemmyTileIsSavedWithItsSort() {
        // Added after "top today" is chosen, technology is checked at its top-today address.
        network(community("linux") to rss("a"), FeedAddress.lemmyUrl(Community("technology", "lemmy.world"), FeedAddress.LemmySort.TOP_DAY) to rss("b"))
        var saved: TileContent.Feed? = null
        editor { saved = it }
        rule.onNodeWithText("Posts open in your browser. Choose a Lemmy app in settings, Metro Plus.").assertExists()
        field().performTextInput("linux@lemmy.ml")
        rule.onNodeWithText("add").performClick()
        waitFor("!linux@lemmy.ml")
        // The sort applies to all the communities.
        tap("top today")
        // Typed but not yet added: "add tile" adds it too.
        field().performTextInput("!technology@lemmy.world")
        tap("add tile")
        rule.waitUntil(5_000) { saved != null }
        assertEquals(
            TileContent.Feed(listOf("https://lemmy.ml/feeds/c/linux.xml?sort=TopDay", "https://lemmy.world/feeds/c/technology.xml?sort=TopDay")),
            saved,
        )
    }

    @Test
    fun eachKindTakesItsOwnFeeds() {
        network("https://example.com/feed" to rss("c"))
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("example.com/feed")
        rule.onNodeWithText("add").performClick()
        waitFor("that isn't a community, like !linux@lemmy.ml. For a site's feed, choose rss or atom.")
        rule.onNodeWithText("rss or atom").performClick()
        field().performTextInput("!linux@lemmy.ml")
        rule.onNodeWithText("add").performClick()
        waitFor("!linux@lemmy.ml is a Lemmy community: choose lemmy above.")
        rule.onNodeWithText("hot").assertDoesNotExist()
        field().performTextReplacement("example.com/feed")
        tap("add tile")
        rule.waitUntil(5_000) { saved != null }
        assertEquals(TileContent.Feed(listOf("https://example.com/feed")), saved)
    }

    @Test
    fun aWholeSiteSaysToAddOneOfItsCommunities() {
        editor { }
        field().performTextInput("https://programming.dev/")
        rule.onNodeWithText("add").performClick()
        waitFor("that's the whole of programming.dev: add one of its communities, like !name@programming.dev or a link with /c/ in it")
    }

    @Test
    fun redditIsRefusedWithTheReason() {
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("r/android")
        rule.onNodeWithText("add").performClick()
        waitFor(FeedAddress.REDDIT_ENDED)
        field().performTextInput("x")
        tap("add tile")
        assertNull(saved)
    }

    @Test
    fun noConnectionOffersAddAnyway() {
        network(community("linux") to IOException("offline"))
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("!linux@lemmy.ml")
        rule.onNodeWithText("add").performClick()
        waitFor("couldn't reach lemmy.ml, check your connection")
        tap("add anyway")
        rule.onNodeWithText("!linux@lemmy.ml").assertExists()
        tap("add tile")
        rule.waitUntil(5_000) { saved != null }
        assertEquals(listOf(community("linux")), saved?.urls)
    }

    @Test
    fun feedsAnotherTileShowsAreRefused() {
        network(community("linux") to rss("a"))
        var saved: TileContent.Feed? = null
        editor(taken = listOf(listOf("https://LEMMY.ml/feeds/c/Linux.xml?sort=Hot"))) { saved = it }
        field().performTextInput("!linux@lemmy.ml")
        tap("add tile")
        waitFor("another tile already shows these feeds")
        assertNull(saved)
    }

    @Test
    fun aWebPageWithoutAFeedIsSaid() {
        network("https://example.com/page" to ("<html><body>no feed</body></html>" to "text/html"))
        var saved: TileContent.Feed? = null
        editor(initial = TileContent.Feed(listOf("https://example.com/old"))) { saved = it }
        rule.onNodeWithText("https://example.com/old").assertExists()
        field().performTextInput("example.com/page")
        tap("done")
        waitFor("that's a web page without a feed")
        assertNull(saved)
    }
}
