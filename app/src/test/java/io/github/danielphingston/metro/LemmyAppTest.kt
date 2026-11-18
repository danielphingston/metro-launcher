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
