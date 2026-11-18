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
import androidx.compose.ui.test.performTextReplacement
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedAddress.RedditFeed
import io.github.danielphingston.metro.live.feed.FeedAddress.RedditSort
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
import org.junit.Assert.assertFalse
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

/** The Reddit subreddit list, the checks a new feed goes through, and the editor that uses them, on a fake network. */
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

    private fun atom(vararg titles: String) =
        "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>t</title>" +
            titles.joinToString("") { "<entry><title>$it</title><link href=\"https://example.com/$it\"/></entry>" } + "</feed>"

    /** Answers by address: a body, or an exception to throw. */
    private fun network(vararg routes: Pair<String, Any>) {
        val map = routes.toMap()
        FeedStore.http = FeedHttp { url, _, _ ->
            when (val answer = map[url] ?: throw HttpStatusException(404)) {
                is Exception -> throw answer
                is Pair<*, *> -> Fetched((answer.first as String).toByteArray(), url, answer.second as String)
                else -> Fetched((answer as String).toByteArray(), url, "application/atom+xml")
            }
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private fun sub(name: String) = FeedAddress.redditUrl(RedditFeed(listOf(name)))

    // ---- subreddit lists ----

    @Test
    fun subredditNamesFromWhatIsTyped() {
        for (input in listOf("android", "r/android", "/r/android/", " R/android ", "reddit.com/r/android", "https://www.reddit.com/r/android/comments/x/y")) {
            assertEquals(input, "android", FeedAddress.subreddit(input))
        }
        for (input in listOf("a", "an droid", "r/", "_android", "way_too_long_a_subreddit_name", "andr*id")) {
            assertNull(input, FeedAddress.subreddit(input))
        }
        assertEquals(listOf("android", "r/pixel", "apple"), FeedAddress.splitSubreddits(" android, r/pixel+apple  "))
    }

    @Test
    fun aRedditFeedReadsBackFromItsAddress() {
        val feed = RedditFeed(listOf("android", "GooglePixel", "apple"), RedditSort.NEW)
        val url = FeedAddress.redditUrl(feed)
        assertEquals("https://www.reddit.com/r/android+GooglePixel+apple/new/.rss", url)
        assertEquals(feed, FeedAddress.reddit(url))
        assertEquals(RedditFeed(listOf("android")), FeedAddress.reddit("https://www.reddit.com/r/android/.rss"))
        assertEquals("r/android +2", FeedAddress.label(url))
        assertEquals("r/android+GooglePixel+apple/new", FeedAddress.shown(url))
        // Not a subreddit list: shown as an address.
        assertNull(FeedAddress.reddit("https://www.reddit.com/user/spez/.rss"))
        assertNull(FeedAddress.reddit("https://www.reddit.com/r/android/top/.rss?t=week"))
        assertNull(FeedAddress.reddit("https://example.com/r/android/.rss"))
    }

    @Test
    fun sameFeedIgnoresOrderCaseAndSlashes() {
        assertTrue(FeedAddress.sameFeed("https://www.reddit.com/r/android+apple/.rss", "https://www.reddit.com/r/Apple+Android/.rss"))
        assertFalse(FeedAddress.sameFeed("https://www.reddit.com/r/android+apple/.rss", "https://www.reddit.com/r/android+apple/new/.rss"))
        assertFalse(FeedAddress.sameFeed("https://www.reddit.com/r/android/.rss", "https://www.reddit.com/r/android+apple/.rss"))
        assertTrue(FeedAddress.sameFeed("https://www.Example.com/feed/", "https://example.com/feed"))
        assertFalse(FeedAddress.sameFeed("https://example.com/feed", "https://example.com/other"))
        assertFalse(FeedAddress.sameFeed("https://example.com/feed?a=1", "https://example.com/feed?a=2"))
    }

    // ---- checking a feed ----

    @Test
    fun eachProblemIsNamed() = runBlocking {
        network(
            "https://ok.example/feed" to atom("one"),
            "https://empty.example/feed" to atom(),
            "https://refused.example/feed" to HttpStatusException(403),
            "https://busy.example/feed" to HttpStatusException(429),
            "https://broken.example/feed" to HttpStatusException(503),
            "https://offline.example/feed" to IOException("unreachable"),
            "https://tls.example/feed" to SSLHandshakeException("bad cert"),
            "https://big.example/feed" to TooLargeException(),
            "https://json.example/feed" to "{\"not\": \"xml\"}",
            "https://page.example/" to ("<!doctype html><html><head><title>Hi</title></head></html>" to "text/html"),
        )
        assertTrue(FeedStore.check(context, "https://ok.example/feed") is FeedCheck.Ok)
        fun problem(url: String) = (runBlocking { FeedStore.check(context, url) } as FeedCheck.Failed)
        assertEquals(FeedProblem.NOT_FOUND, problem("https://missing.example/feed").problem)
        assertEquals("nothing at that address (404)", problem("https://missing.example/feed").message)
        assertEquals(FeedProblem.EMPTY, problem("https://empty.example/feed").problem)
        assertEquals(FeedProblem.REFUSED, problem("https://refused.example/feed").problem)
        assertEquals(FeedProblem.BUSY, problem("https://busy.example/feed").problem)
        assertEquals("the site has a problem (503)", problem("https://broken.example/feed").message)
        assertEquals(FeedProblem.OFFLINE, problem("https://offline.example/feed").problem)
        assertEquals(FeedProblem.INSECURE, problem("https://tls.example/feed").problem)
        assertEquals(FeedProblem.TOO_LARGE, problem("https://big.example/feed").problem)
        assertEquals(FeedProblem.NOT_A_FEED, problem("https://json.example/feed").problem)
        assertEquals(FeedProblem.WEB_PAGE, problem("https://page.example/").problem)
        // Only problems that may pass offer "add anyway".
        assertEquals(
            setOf(FeedProblem.BUSY, FeedProblem.SERVER, FeedProblem.OFFLINE, FeedProblem.EMPTY),
            FeedProblem.entries.filter { it.retryable }.toSet(),
        )
    }

    @Test
    fun aPageLeadsToTheFeedItNames() = runBlocking {
        val page = """<html><head>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/rss+xml" title="Posts" href="/blog/feed.xml?x=1&amp;y=2">
            </head><body>hi</body></html>"""
        network(
            "https://blog.example/" to (page to "text/html; charset=utf-8"),
            "https://blog.example/blog/feed.xml?x=1&y=2" to atom("first post"),
        )
        val result = FeedStore.check(context, "https://blog.example/") as FeedCheck.Ok
        assertEquals("https://blog.example/blog/feed.xml?x=1&y=2", result.url)
        // The good copy is kept, so the new tile shows it straight away.
        assertEquals("first post", FeedStore.feeds.value[result.url]?.feed?.items?.single()?.title)
        // A page naming a feed over plain http gets the https one; a page naming none is a page.
        assertEquals("https://x.example/f", FeedStore.discover("""<link href="http://x.example/f" type="application/atom+xml" rel="alternate">""", "https://x.example/"))
        assertNull(FeedStore.discover("""<link rel="alternate" hreflang="de" href="/de/">""", "https://x.example/"))
    }

    @Test
    fun subredditProblemsNameTheSubreddit() {
        assertEquals("r/nope doesn't exist", FeedProblem.NOT_FOUND.subredditMessage("nope"))
        assertEquals("r/secret is private, quarantined or banned", FeedProblem.REFUSED.subredditMessage("secret"))
        assertEquals("r/quiet has no posts", FeedProblem.EMPTY.subredditMessage("quiet"))
    }

    // ---- the editor ----

    private fun editor(initial: String? = null, taken: Set<String> = emptySet(), onDone: (String) -> Unit) {
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    FeedEditor(initial, isTaken = { url -> taken.any { FeedAddress.sameFeed(it, url) } }, onDone = onDone, onCancel = {})
                }
            }
        }
    }

    /** The editor's one text field. */
    private fun field() = rule.onNode(hasSetTextAction())

    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun subredditsAreCheckedAsTheyAreAdded() {
        network(sub("android") to atom("a"), sub("GooglePixel") to atom("b"), sub("secret") to HttpStatusException(403))
        var saved: String? = null
        editor { saved = it }
        field().performTextInput("android, r/GooglePixel nonexistent")
        rule.onNodeWithText("add").performClick()
        waitFor("r/nonexistent doesn't exist")
        rule.onNodeWithText("r/android").assertExists()
        rule.onNodeWithText("r/GooglePixel").assertExists()
        rule.onNodeWithText("add anyway").assertDoesNotExist()

        // The name that failed stays in the field to fix, and "add tile" tries it again rather than dropping it.
        rule.onNodeWithText("nonexistent").assertExists()
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("checking r/nonexistent…").fetchSemanticsNodes().isEmpty() }
        waitFor("r/nonexistent doesn't exist")
        assertNull(saved)
        // Removing one takes it off the list.
        rule.onAllNodesWithText("remove")[0].performClick()
        rule.onNodeWithText("r/android").assertDoesNotExist()
        rule.onNodeWithText("r/GooglePixel").assertExists()
    }

    @Test
    fun aRedditListIsSavedAsOneFeed() {
        network(sub("android") to atom("a"), sub("apple") to atom("b"))
        var saved: String? = null
        editor { saved = it }
        field().performTextInput("android")
        rule.onNodeWithText("add").performClick()
        waitFor("r/android")
        // Typed but not yet added: "add tile" adds it too.
        field().performTextInput("apple")
        rule.onNodeWithText("top").performClick()
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
        assertEquals("https://www.reddit.com/r/android+apple/top/.rss", saved)
    }

    @Test
    fun noConnectionOffersAddAnyway() {
        network(sub("android") to IOException("offline"))
        var saved: String? = null
        editor { saved = it }
        field().performTextInput("android")
        rule.onNodeWithText("add").performClick()
        waitFor("couldn't connect, check your connection")
        rule.onNodeWithText("add anyway").performClick()
        rule.onNodeWithText("r/android").assertExists()
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
        assertEquals("https://www.reddit.com/r/android/.rss", saved)
    }

    @Test
    fun aFeedAnotherTileShowsIsRefused() {
        network(sub("android") to atom("a"))
        var saved: String? = null
        editor(taken = setOf("https://www.reddit.com/r/Android/.rss")) { saved = it }
        field().performTextInput("android")
        rule.onNodeWithText("add tile").performClick()
        waitFor("another tile already shows this feed")
        assertNull(saved)
    }

    @Test
    fun anRssAddressIsReadBeforeItIsAccepted() {
        network("https://example.com/feed" to atom("x"), "https://example.com/page" to ("<html><body>no feed</body></html>" to "text/html"))
        var saved: String? = null
        editor { saved = it }
        rule.onNodeWithText("rss or atom").performClick()
        field().performTextInput("not an address")
        rule.onNodeWithText("add tile").performClick()
        waitFor("that isn't a web address")
        assertNull(saved)
    }

    @Test
    fun aWebPageWithoutAFeedIsSaid() {
        network("https://example.com/page" to ("<html><body>no feed</body></html>" to "text/html"))
        var saved: String? = null
        editor(initial = "https://example.com/old") { saved = it }
        field().performTextReplacement("https://example.com/page")
        rule.onNodeWithText("done").performClick()
        waitFor("that's a web page without a feed")
        assertNull(saved)
    }

    @Test
    fun aGoodRssAddressIsSaved() {
        network("https://example.com/feed" to atom("x"))
        var saved: String? = null
        editor { saved = it }
        rule.onNodeWithText("rss or atom").performClick()
        field().performTextInput("example.com/feed")
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
        assertEquals("https://example.com/feed", saved)
    }
}
