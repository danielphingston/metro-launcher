package io.github.danielphingston.metro

import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the settings menu and each of its pages on the JVM. Output: app/build/outputs/roborazzi/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun menu() = capture("settings_menu", null)

    @Test
    fun start() = capture("settings_start", SettingsPage.START)

    @Test
    fun appList() = capture("settings_app_list", SettingsPage.APP_LIST)

    @Test
    fun search() = capture("settings_search", SettingsPage.SEARCH)

    @Test
    fun appearance() {
        // The icon pack list loads off the main thread: wait for it, or the capture can miss it.
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        compose.setContent { MetroTheme(settings) { SettingsScreen(settings, repository, SettingsPage.APPEARANCE) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("no icon packs installed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/settings_appearance.png", roborazziOptions = ScreenshotOptions)
    }

    @Test
    fun tiles() = capture("settings_tiles", SettingsPage.TILES)

    @Test
    fun metroPlus() = capture("settings_metro_plus", SettingsPage.METRO_PLUS)

    @Test
    fun about() = capture("settings_about", SettingsPage.ABOUT)

    private fun capture(name: String, page: SettingsPage?) {
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) { SettingsScreen(settings, repository, page) }
        }
    }
}
