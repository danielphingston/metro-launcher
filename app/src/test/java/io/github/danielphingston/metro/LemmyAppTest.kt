package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.settings.FeedTileSettings
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.FeedTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Lemmy app setting: which installed apps count as Lemmy apps (they open post links on known
 * instances, unlike a browser, which opens any), and where a Lemmy tile's posts then open.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LemmyAppTest {
    private val context get() = RuntimeEnvironment.getApplication()

    /** Installs [pkg] named [label] with one activity taking https links on [hosts] (any host when empty). */
    private fun install(pkg: String, label: String, hosts: List<String>) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                }
            },
        )
        val component = ComponentName(pkg, "$pkg.Main")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                hosts.forEach { addDataAuthority(it, null) }
            },
        )
    }

    @Before
    fun apps() {
        install("com.example.browser", "Browser", emptyList())
        install("com.jerboa", "Jerboa", listOf("lemmy.world", "lemmy.ml", "programming.dev"))
    }

    @Test
    fun lemmyAppsAreFoundAndBrowsersLeftOut() {
        assertEquals(listOf(FeedTiles.LinkApp("com.jerboa", "Jerboa")), FeedTiles.lemmyApps(context))
        assertTrue(FeedTiles.opens(context, "com.jerboa", "programming.dev"))
        assertFalse(FeedTiles.opens(context, "com.jerboa", "feddit.org"))
    }

    @Test
    fun aLemmyTilesPostsOpenInTheChosenAppWhereItTakesThem() {
        val programming = TileContent.Feed(listOf("https://programming.dev/feeds/c/programming.xml?sort=Hot"))
        assertEquals("com.jerboa", FeedTiles.intent(context, 1, programming, "com.jerboa").`package`)
        // No app chosen, an instance the app doesn't list, or an RSS tile: the system decides.
        assertNull(FeedTiles.intent(context, 1, programming, null).`package`)
        assertNull(FeedTiles.intent(context, 1, TileContent.Feed(listOf("https://feddit.org/feeds/c/x.xml")), "com.jerboa").`package`)
        assertNull(FeedTiles.intent(context, 1, TileContent.Feed(listOf("https://lemmy.world/feed.xml")), "com.jerboa").`package`)
    }

    @Test
    fun aPostSeenThroughAnotherInstanceOpensByItsOwnAddress() {
        // The feed's link, already aimed at Jerboa (which lists programming.dev).
        val plain = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://programming.dev/post/59546847")).setPackage("com.jerboa")
        // Written on lemmy.ml, which Jerboa lists: its own address goes to Jerboa.
        val listed = FeedTiles.canonicalIntent(context, "https://lemmy.ml/post/123", "com.jerboa", plain)
        assertEquals("https://lemmy.ml/post/123", listed.data.toString())
        assertEquals("com.jerboa", listed.`package`)
        // Written on lemmy.today, which it doesn't: the feed's link opens in the browser instead of "can't find".
        val unlisted = FeedTiles.canonicalIntent(context, "https://lemmy.today/post/63466711", "com.jerboa", plain)
        assertEquals("https://programming.dev/post/59546847", unlisted.data.toString())
        assertNull(unlisted.`package`)
        // Written where the feed is, or not looked up: as it was.
        assertTrue(FeedTiles.canonicalIntent(context, "https://programming.dev/post/59546847", "com.jerboa", plain) === plain)
        assertTrue(FeedTiles.canonicalIntent(context, null, "com.jerboa", plain) === plain)
    }

    @Test
    fun aSlowInstanceDoesNotHoldUpTheTap() = kotlinx.coroutines.runBlocking {
        val original = io.github.danielphingston.metro.live.feed.FeedStore.http
        val released = java.util.concurrent.CountDownLatch(1)
        io.github.danielphingston.metro.live.feed.FeedStore.http = io.github.danielphingston.metro.live.feed.FeedHttp { url, _, _ ->
            // A server that stalls: the download blocks its thread, which no timeout can interrupt.
            released.await(10, java.util.concurrent.TimeUnit.SECONDS)
            io.github.danielphingston.metro.live.feed.Fetched(
                """{"post_view":{"post":{"ap_id":"https://lemmy.ml/post/7"}}}""".toByteArray(), url, "application/json",
            )
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val link = "https://programming.dev/post/42"
            val plain = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage("com.jerboa")
            val started = System.nanoTime()
            val first = FeedTiles.lemmyIntent(context, scope, link, "com.jerboa", plain, waitMs = 300)
            val waited = (System.nanoTime() - started) / 1_000_000
            // Gave up waiting after about 300 ms and opened the feed's link in the app.
            assertTrue("waited $waited ms", waited < 2_000)
            assertTrue(first === plain)
            // The answer that arrives later is remembered: the next tap uses the post's own address.
            released.countDown()
            kotlinx.coroutines.withTimeout(5_000) {
                while (io.github.danielphingston.metro.live.feed.FeedStore.canonicalPostLink(link) == null) kotlinx.coroutines.delay(20)
            }
            assertEquals("https://lemmy.ml/post/7", FeedTiles.lemmyIntent(context, scope, link, "com.jerboa", plain).data.toString())
        } finally {
            released.countDown()
            scope.cancel()
            io.github.danielphingston.metro.live.feed.FeedStore.http = original
            io.github.danielphingston.metro.live.feed.FeedStore.clearMemory()
        }
    }

    @Test
    fun theSetting() {
        captureRoboImage("src/test/screenshots/settings_lemmy_app.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Column(Modifier.fillMaxSize().background(Metro.colors.background).padding(horizontal = 24.dp)) {
                    FeedTileSettings(LauncherSettings(lemmyApp = "com.jerboa"), SettingsRepository(context))
                }
            }
        }
    }
}
