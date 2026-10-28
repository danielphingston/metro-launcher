package io.github.danielphingston.metro

import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Test
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
    @Test
    fun menu() = capture("settings_menu", null)

    @Test
    fun start() = capture("settings_start", SettingsPage.START)

    @Test
    fun appList() = capture("settings_app_list", SettingsPage.APP_LIST)

    @Test
    fun search() = capture("settings_search", SettingsPage.SEARCH)

    @Test
    fun appearance() = capture("settings_appearance", SettingsPage.APPEARANCE)

    @Test
    fun tiles() = capture("settings_tiles", SettingsPage.TILES)

    @Test
    fun quickTiles() = capture("settings_quick_tiles", SettingsPage.QUICK_TILES)

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
