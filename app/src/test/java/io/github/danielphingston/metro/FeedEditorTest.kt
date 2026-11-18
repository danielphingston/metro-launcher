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
import androidx.compose.ui.test.performTextInput
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

    @After
    fun restore() {
        FeedStore.http = original
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
                    FeedEditor(initial, isTaken = { urls -> taken.any { FeedAddress.sameFeeds(it, urls) } }, onDone = onDone, onCancel = {})
                }
            }
        }
    }

    private fun field() = rule.onNode(hasSetTextAction())
    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun communitiesAndFeedsAreCheckedAsTheyAreAdded() {
        network(community("linux") to rss("a"), community("technology", "lemmy.world") to rss("b"), "https://example.com/feed" to rss("c"))
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("!linux@lemmy.ml lemmy.world/c/technology example.com/feed !nope@lemmy.ml")
        rule.onNodeWithText("add").performClick()
        waitFor("!nope@lemmy.ml wasn't found on lemmy.ml")
        rule.onNodeWithText("!linux@lemmy.ml").assertExists()
        rule.onNodeWithText("!technology@lemmy.world").assertExists()
        rule.onNodeWithText("https://example.com/feed").assertExists()
        rule.onNodeWithText("add anyway").assertDoesNotExist()
        // The entry that failed stays in the field to fix, and "add tile" tries it again rather than dropping it.
        rule.onNodeWithText("!nope@lemmy.ml").assertExists()
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("checking !nope@lemmy.ml…").fetchSemanticsNodes().isEmpty() }
        assertNull(saved)
        // Removing one takes it off the list.
        rule.onAllNodesWithText("remove")[0].performClick()
        rule.onNodeWithText("!linux@lemmy.ml").assertDoesNotExist()
    }

    @Test
    fun theTileIsSavedWithItsSortAndFeeds() {
        network(community("linux") to rss("a"), "https://example.com/feed" to rss("c"))
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("linux@lemmy.ml")
        rule.onNodeWithText("add").performClick()
        waitFor("!linux@lemmy.ml")
        // The sort shows once there is a community, and applies to all of them.
        rule.onNodeWithText("top today").performClick()
        // Typed but not yet added: "add tile" adds it too.
        field().performTextInput("example.com/feed")
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
        assertEquals(TileContent.Feed(listOf("https://lemmy.ml/feeds/c/linux.xml?sort=TopDay", "https://example.com/feed")), saved)
    }

    @Test
    fun redditIsRefusedWithTheReason() {
        var saved: TileContent.Feed? = null
        editor { saved = it }
        field().performTextInput("r/android")
        rule.onNodeWithText("add").performClick()
        waitFor(FeedAddress.REDDIT_ENDED)
        field().performTextInput("x")
        rule.onNodeWithText("add tile").performClick()
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
        rule.onNodeWithText("add anyway").performClick()
        rule.onNodeWithText("!linux@lemmy.ml").assertExists()
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
        assertEquals(listOf(community("linux")), saved?.urls)
    }

    @Test
    fun feedsAnotherTileShowsAreRefused() {
        network(community("linux") to rss("a"))
        var saved: TileContent.Feed? = null
        editor(taken = listOf(listOf("https://LEMMY.ml/feeds/c/Linux.xml?sort=Hot"))) { saved = it }
        field().performTextInput("!linux@lemmy.ml")
        rule.onNodeWithText("add tile").performClick()
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
        rule.onNodeWithText("done").performClick()
        waitFor("that's a web page without a feed")
        assertNull(saved)
    }
}
