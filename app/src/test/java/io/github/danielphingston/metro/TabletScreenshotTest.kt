package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.StartPage
import io.github.danielphingston.metro.ui.start.StartColumns
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** The start screen with the automatic column count on tablets, and the choice in the settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-mdpi", application = android.app.Application::class)
class TabletScreenshotTest {
    private val start = StartScreenshotTest()

    @Test
    fun startAutoPortrait() = captureStart("start_auto_tablet_portrait")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun startAutoLandscape() = captureStart("start_auto_tablet_landscape")

    @Test
    fun settingsStartTablet() = captureRoboImage("src/test/screenshots/settings_start_tablet.png", roborazziOptions = ScreenshotOptions) {
        val context = LocalContext.current
        val repository = remember { SettingsRepository(context) }
        MetroTheme(LauncherSettings()) {
            Column(Modifier.fillMaxSize().background(Metro.colors.background).padding(start = 20.dp, end = 20.dp, top = 40.dp)) {
                MetroText("start+theme", MetroType.pageTitle, maxLines = 1)
                StartPage(LauncherSettings(), repository)
            }
        }
    }

    private fun captureStart(name: String) = captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) { AutoStart() }
    }

    /** The preview start screen with the columns the real one picks for the width (8dp paddings, 4dp gaps). */
    @Composable
    private fun AutoStart() {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = StartColumns.auto(maxWidth.value - 16f + 4f)
            with(start) { StartPreview(samples() + samples().shuffled(Random(7)) + samples(), columns, StartBackdrop.Solid) }
        }
    }
}
