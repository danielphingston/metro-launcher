package io.github.danielphingston.metro

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

/** Settings at 200% font size: labels and summaries wrap instead of being cut off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SettingsA11yScreenshotTest {
    @Test
    fun menuAtLargeFont() = capture("settings_menu_font_2x", null)

    @Test
    fun startAtLargeFont() = capture("settings_start_font_2x", SettingsPage.START)

    @Test
    fun appListAtLargeFont() = capture("settings_app_list_font_2x", SettingsPage.APP_LIST)

    private fun capture(name: String, page: SettingsPage?) {
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        captureRoboImage("src/test/screenshots/$name.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MetroTheme(settings) { SettingsScreen(settings, repository, page) }
            }
        }
    }
}
