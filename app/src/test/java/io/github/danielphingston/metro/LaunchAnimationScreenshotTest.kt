package io.github.danielphingston.metro

import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LaunchStyle
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.start.Turnstile
import io.github.danielphingston.metro.ui.start.TurnstilePhase
import io.github.danielphingston.metro.ui.start.TurnstileState
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The start screen frozen half way through each launch animation style, and the animations
 * settings page. The tapped tile is "Messages" (second tile, top right of the first row of two).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class LaunchAnimationScreenshotTest {
    private val start = StartScreenshotTest()

    /** Messages' bounds in px at 3x: 8dp padding, 52dp top, 99.75dp cells, 2x2 cells less the gap. */
    private val tapped = Rect(622f, 156f, 1207f, 741f)

    @Test
    fun turnstile() = midExit("launch_turnstile", LaunchStyle.TURNSTILE)

    @Test
    fun turnstileRows() = midExit("launch_turnstile_rows", LaunchStyle.TURNSTILE_ROWS)

    @Test
    fun slide() = midExit("launch_slide", LaunchStyle.SLIDE)

    @Test
    fun zoom() = midExit("launch_zoom", LaunchStyle.ZOOM)

    @Test
    fun fade() = midExit("launch_fade", LaunchStyle.FADE)

    @Test
    fun animationsPage() {
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        captureRoboImage("src/test/screenshots/settings_animations.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(settings) { SettingsScreen(settings, repository, SettingsPage.ANIMATIONS) }
        }
    }

    /** NONE has no frame to show: the launcher stays as it is while the system reveals the app. */
    private fun midExit(name: String, style: LaunchStyle) {
        val frozen = TurnstileState().apply {
            seek(TurnstilePhase.OUT, Turnstile.spec(style).totalMs / 2f, tapped, style)
        }
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(columns = 4)) {
                val backdrop = remember { StartBackdrop.of(LauncherSettings(), null) { 0f } }
                start.StartPreview(start.samples(), 4, backdrop, turnstile = frozen)
            }
        }
    }
}
