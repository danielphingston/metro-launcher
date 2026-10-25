package io.github.danielphingston.metro

import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileFace
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A live tile that is showing its back face when the last notification goes away: the card flips
 * back over 650 ms, and until it passes the halfway point the back face is still composed, with
 * no live content left to draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LiveTileFlipTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = AppInfo(
        key = AppKey("com.example.messages", "Main", 0),
        label = "Messages",
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> ColorDrawable(0xFF444444.toInt()) },
    )

    @Test
    fun contentThatVanishesWhileTheBackIsShownDoesNotCrash() {
        val icons = IconCache(ApplicationProvider.getApplicationContext())
        val messages = AppNotifications(1, listOf(NotificationPreview("Mom", "Dinner tonight?", 0)))
        var live by mutableStateOf<LiveContent?>(LiveContent.Notifications(messages, showContent = true))
        var flipped by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MetroTheme(LauncherSettings()) {
                TileFace(app, TileSize.WIDE, live, flipped, monochromeIcons = false, icons = icons, cellSize = 96.dp)
            }
        }
        rule.mainClock.advanceTimeBy(1_000) // Flipped over: the back face is showing.
        rule.onNodeWithText("Dinner tonight?").assertExists()

        // The notification is dismissed: the tile flips back, but its back face stays up for a while.
        rule.runOnUiThread {
            live = null
            flipped = false
        }
        repeat(50) { rule.mainClock.advanceTimeBy(16) } // Frame by frame through the 650 ms flip.
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Messages").assertExists() // The front again.
    }
}
