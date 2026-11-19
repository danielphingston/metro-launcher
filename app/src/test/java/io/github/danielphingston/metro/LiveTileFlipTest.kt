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
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.tiles.MeCycle
import io.github.danielphingston.metro.ui.tiles.MeFace
import io.github.danielphingston.metro.ui.tiles.MeModel
import io.github.danielphingston.metro.ui.tiles.MeShown
import io.github.danielphingston.metro.ui.tiles.MeTile
import org.junit.Assert.assertEquals
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

    private fun face(key: String) = MeFace(key, "app-$key", "App $key", "Title $key", "Text $key")
    private fun meModel(vararg keys: String) =
        MeModel(MeModel.State.ACTIVE, keys.size, keys.map { "app-$it" }, keys.map(::face))

    @Test
    fun meTileFlipsToItsNotificationsSlidesThroughThemAndFlipsBack() {
        val icons = IconCache(ApplicationProvider.getApplicationContext())
        val tile = ResolvedTile(TileSpec(1, TileSize.WIDE, TileContent.Me), null, "me")
        var live by mutableStateOf<LiveContent.Me?>(LiveContent.Me(meModel("a", "b", "c"), emptyMap()))
        var flipped by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MetroTheme(LauncherSettings()) { MeTile(tile, live, flipped, monochrome = false, icons = icons, cellSize = 96.dp) }
        }
        fun turn() {
            rule.runOnUiThread { flipped = !flipped }
            rule.waitForIdle()
            repeat(40) { rule.mainClock.advanceTimeBy(16) } // frame by frame through the flip or slide
            rule.mainClock.advanceTimeBy(1_000)
        }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(null, MeShown.key) // the count
        turn(); rule.onNodeWithText("Title a").assertExists(); assertEquals("a", MeShown.key)
        turn(); rule.onNodeWithText("Title b").assertExists(); assertEquals("b", MeShown.key)
        // A new notification arrives mid-way: the tile stays on b, and goes on from there.
        rule.runOnUiThread { live = LiveContent.Me(meModel("d", "a", "b", "c"), emptyMap()) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("b", MeShown.key)
        turn(); rule.onNodeWithText("Title c").assertExists(); assertEquals("c", MeShown.key)
        turn(); assertEquals(null, MeShown.key) // after the last: back to the count
        turn(); assertEquals("d", MeShown.key)
        // Everything is cleared while a notification shows, part-way through a slide: no crash, back to the count.
        rule.runOnUiThread { flipped = !flipped }
        rule.waitForIdle()
        repeat(10) { rule.mainClock.advanceTimeBy(16) }
        rule.runOnUiThread { live = LiveContent.Me(MeModel(MeModel.State.EMPTY, 0, emptyList(), emptyList()), emptyMap()) }
        rule.waitForIdle()
        repeat(60) { rule.mainClock.advanceTimeBy(16) }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(null, MeShown.key)
        rule.onNodeWithText("nothing new").assertExists()
        // And no live content at all.
        rule.runOnUiThread { live = null }
        rule.waitForIdle()
        turn(); turn()
        assertEquals(null, MeShown.key)
    }

    @Test
    fun meCycleSteps() {
        val c = MeCycle()
        val abc = listOf(face("a"), face("b"), face("c"))
        assertEquals(-1, c.at(false, abc)) // starts on the count
        assertEquals(-1, c.at(false, abc)) // no turn, no step
        assertEquals(0, c.at(true, abc))
        assertEquals(1, c.at(false, abc))
        assertEquals(2, c.at(true, abc))
        assertEquals(-1, c.at(false, abc)) // after the last, the count
        assertEquals(0, c.at(true, abc))
        // The shown one is removed: the next one takes its place.
        assertEquals(0, c.at(true, listOf(face("b"), face("c"))))
        // Its own place moved: follow it.
        assertEquals(1, c.at(true, listOf(face("x"), face("b"), face("c"))))
        // Nothing left: the count, and a turn stays there.
        assertEquals(-1, c.at(true, emptyList()))
        assertEquals(-1, c.at(false, emptyList()))
        // One notification: count, it, count.
        assertEquals(0, c.at(true, listOf(face("z"))))
        assertEquals(-1, c.at(false, listOf(face("z"))))
    }
}
