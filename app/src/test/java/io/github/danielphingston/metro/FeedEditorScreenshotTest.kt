package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.live.feed.FeedHttp
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.live.feed.Fetched
import io.github.danielphingston.metro.live.feed.HttpStatusException
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.FeedEditor
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The feed editor: a Reddit list with a subreddit that doesn't exist, and an RSS address that is a web page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class FeedEditorScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val original = FeedStore.http

    @After
    fun restore() {
        FeedStore.http = original
        FeedStore.clearMemory()
    }

    private fun network(ok: Set<String>) {
        FeedStore.http = FeedHttp { url, _, _ ->
            when {
                url in ok -> Fetched("<feed><title>t</title><entry><title>x</title></entry></feed>".toByteArray(), url, "application/atom+xml")
                url.startsWith("https://www.reddit.com/") -> throw HttpStatusException(404)
                else -> Fetched("<html><body>hello</body></html>".toByteArray(), url, "text/html")
            }
        }
    }

    private fun show(initial: String?) = rule.setContent {
        MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
            Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                FeedEditor(initial, isTaken = { false }, onDone = {}, onCancel = {})
            }
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun reddit() {
        network(setOf("https://www.reddit.com/r/Android/.rss", "https://www.reddit.com/r/GooglePixel/.rss", "https://www.reddit.com/r/androidapps/.rss"))
        show(null)
        rule.onNode(hasSetTextAction()).performTextInput("Android GooglePixel androidapps androidd")
        rule.onNodeWithText("add").performClick()
        waitFor("r/androidd doesn't exist")
        rule.onNodeWithText("new").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/feed_editor_reddit.png", roborazziOptions = ScreenshotOptions)
    }

    @Test
    fun rss() {
        network(emptySet())
        show(null)
        rule.onNodeWithText("rss or atom").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("example.com")
        rule.onNodeWithText("add tile").performClick()
        waitFor("that's a web page without a feed")
        rule.onRoot().captureRoboImage("src/test/screenshots/feed_editor_rss.png", roborazziOptions = ScreenshotOptions)
    }
}
