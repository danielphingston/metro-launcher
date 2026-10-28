package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ScrubberHighlight
import io.github.danielphingston.metro.ui.applist.AlphabetScrubber
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the app list's alphabet scrubber with a finger held on it. Output: app/build/outputs/roborazzi/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScrubberScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val letters = ("#abcdefghijklmnopqrstuvwxyz").map { it.toString() }

    private fun shoot(highlight: ScrubberHighlight, name: String) {
        rule.setContent {
            MetroTheme(LauncherSettings()) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    AlphabetScrubber(
                        letters,
                        onScrub = {},
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().testTag("scrubber"),
                        highlight = highlight,
                    )
                }
            }
        }
        // Hold a finger on "m", roughly in the middle of the strip.
        rule.onNodeWithTag("scrubber").performTouchInput { down(center.copy(y = height * 0.72f)) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onRoot().captureRoboImage("src/test/screenshots/scrubber_$name.png", roborazziOptions = ScreenshotOptions)
    }

    @Test fun circle() = shoot(ScrubberHighlight.CIRCLE, "circle")

    @Test fun roundedRect() = shoot(ScrubberHighlight.ROUNDED_RECT, "rounded_rect")
}
