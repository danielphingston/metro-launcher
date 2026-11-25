package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.settings.StartLayoutsSectionContent
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The layouts section with its "same grid" switch, and settings search: results and a found setting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class LayoutsAndSearchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val twoLayouts = LayoutSet(1, listOf(SavedLayout(1, "start", "[]"), SavedLayout(2, "work", "[]")))

    private fun layoutsSection(name: String, sameGrid: Boolean, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MetroTheme(LauncherSettings()) {
                    Column(Modifier.fillMaxSize().background(Metro.colors.background).padding(horizontal = 20.dp)) {
                        StartLayoutsSectionContent(true, twoLayouts, {}, {}, {}, sameGrid = sameGrid)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions)
    }

    @Test
    fun sameGridOn() = layoutsSection("layouts_same_grid_on", sameGrid = true)

    @Test
    fun sameGridOff() = layoutsSection("layouts_same_grid_off", sameGrid = false)

    @Test
    fun sameGridLargeFont() = layoutsSection("layouts_same_grid_font_2x", sameGrid = true, fontScale = 2f)

    private fun settingsScreen() {
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        compose.setContent { MetroTheme(settings) { SettingsScreen(settings, repository) } }
    }

    @Test
    fun searchResults() {
        settingsScreen()
        compose.onNode(hasSetTextAction()).performTextInput("wallpaper")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/settings_search_results.png", roborazziOptions = ScreenshotOptions)
    }

    /** A result opens its page with the setting in view, flashing; caught while the flash holds. */
    @Test
    fun foundSettingFlashes() {
        settingsScreen()
        compose.onNode(hasSetTextAction()).performTextInput("swing")
        compose.waitForIdle()
        // Stop the clock from here, so the capture falls inside the flash instead of after it.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("swing pages").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("src/test/screenshots/settings_search_found.png", roborazziOptions = ScreenshotOptions)
    }
}
