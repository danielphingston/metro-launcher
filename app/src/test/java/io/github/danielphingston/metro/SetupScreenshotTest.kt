package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.onboarding.BackgroundStep
import io.github.danielphingston.metro.ui.onboarding.OnboardingScreen
import io.github.danielphingston.metro.ui.onboarding.OnboardingStep
import io.github.danielphingston.metro.ui.onboarding.StartGridStep
import io.github.danielphingston.metro.ui.start.CoachMark
import io.github.danielphingston.metro.ui.start.CoachMarkBar
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The setup's start screen steps and the hints after it, at the normal and a doubled font size:
 * nothing may overflow or be cut off. Output: app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SetupScreenshotTest {
    /** A start screen like the default one: mediums, wides and smalls. */
    private val tiles = listOf(
        TileSize.MEDIUM, TileSize.MEDIUM, TileSize.WIDE, TileSize.WIDE, TileSize.MEDIUM, TileSize.MEDIUM,
        TileSize.WIDE, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.MEDIUM,
    ).mapIndexed { i, size -> TileSpec(i + 1L, size, TileContent.App(AppKey("p$i", "p$i.Main", 0))) }

    private fun capture(name: String, settings: LauncherSettings = LauncherSettings(), fontScale: Float = 1f, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MetroTheme(settings) {
                    Box(Modifier.fillMaxSize().background(Metro.colors.background)) { content() }
                }
            }
        }
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
    }

    private val repository get() = SettingsRepository(RuntimeEnvironment.getApplication())

    /** Each step of the setup as the user sees it, full screen, at the normal and a doubled font size. */
    @Test
    fun everyStep() {
        for (step in OnboardingStep.entries) {
            for (scale in listOf(1f, 2f)) {
                val name = "setup_step_${step.ordinal + 1}_${step.name.lowercase()}" + if (scale > 1f) "_font_2x" else ""
                capture(name, LauncherSettings(columns = 6), scale) { OnboardingScreen(LauncherSettings(columns = 6), {}, step) }
            }
        }
    }

    @Test
    fun gridStep() = capture("setup_grid") { Page { StartGridStep(LauncherSettings(columns = 6), repository, tiles) } }

    @Test
    fun gridStepTwoPerRowLight() {
        val settings = LauncherSettings(columns = 4, theme = ThemeMode.LIGHT, startLeftPadding = 20, startRightPadding = 20)
        capture("setup_grid_light", settings) { Page { StartGridStep(settings, repository, tiles) } }
    }

    @Test
    fun gridStepLargeFont() = capture("setup_grid_font_2x", fontScale = 2f) {
        Page { StartGridStep(LauncherSettings(columns = 6), repository, tiles) }
    }

    @Test
    fun backgroundStep() = capture("setup_background") { Page { BackgroundStep(LauncherSettings(), repository) } }

    @Test
    fun backgroundStepLargeFont() = capture("setup_background_font_2x", fontScale = 2f) {
        Page { BackgroundStep(LauncherSettings(), repository) }
    }

    @Test
    fun hints() = capture("setup_hints") {
        Box(Modifier.fillMaxSize()) {
            CoachMarkBar(CoachMark.HOLD_TILE) {}
            CoachMarkBar(CoachMark.EDITING) {}
        }
    }

    @Test
    fun hintsLargeFont() = capture("setup_hints_font_2x", fontScale = 2f) {
        Box(Modifier.fillMaxSize()) {
            CoachMarkBar(CoachMark.APP_LIST) {}
            CoachMarkBar(CoachMark.EDITING) {}
        }
    }
}
