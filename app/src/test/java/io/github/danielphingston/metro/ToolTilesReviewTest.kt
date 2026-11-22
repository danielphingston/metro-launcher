package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.InkCodec
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.NoteItem
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.ToolLabels
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.CoinTile
import io.github.danielphingston.metro.ui.tiles.CountdownTile
import io.github.danielphingston.metro.ui.tiles.DiceTile
import io.github.danielphingston.metro.ui.tiles.LocalToolClock
import io.github.danielphingston.metro.ui.tiles.NotesTile
import io.github.danielphingston.metro.ui.tiles.RoutineTile
import io.github.danielphingston.metro.ui.tiles.ToolMemory
import io.github.danielphingston.metro.ui.tiles.rememberTileMetrics
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Every tool tile at every size with the content most likely to overflow (long names, six doses,
 * long lists and notes, big numbers), at normal and large font sizes. Kept as goldens so a later
 * change that makes one spill out of its tile fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi", application = android.app.Application::class)
class ToolTilesReviewTest {
    @get:Rule
    val rule = createComposeRule()

    private var zoneBefore: TimeZone? = null

    @Before
    fun fixZone() {
        zoneBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun restore() {
        zoneBefore?.let(TimeZone::setDefault)
        ToolMemory.tosses.clear()
        ToolMemory.rolls.clear()
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val now get() = at(21, 14)

    /** [content] small, medium, wide and large, laid out as on start. */
    private fun sizes(name: String, content: TileContent.Tool, icon: String? = null, fontScale: Float = 1f) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val items = listOf(TileSize.SMALL to (0 to 0), TileSize.MEDIUM to (1 to 0), TileSize.WIDE to (0 to 2), TileSize.LARGE to (0 to 4))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalToolClock provides { now }, LocalDensity provides Density(density.density, fontScale)) {
                MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                    Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 24.dp)) {
                            val gap = 4.dp
                            val c = (maxWidth + gap) / 4
                            items.forEachIndexed { i, (size, pos) ->
                                val spec = TileSpec(100L + i, size, content, icon = icon)
                                val tile = ResolvedTile(spec, null, ToolLabels.label(content))
                                Box(Modifier.offset(c * pos.first, c * pos.second).size(c * size.width - gap, c * size.height - gap)) {
                                    Face(tile, c, icons)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/review_$name.png", roborazziOptions = ScreenshotOptions)
    }

    @Composable
    private fun Face(tile: ResolvedTile, cell: Dp, icons: IconCache) {
        val m = rememberTileMetrics(cell)
        when (tile.spec.content) {
            is TileContent.Routine -> RoutineTile(tile, m, icons)
            is TileContent.Countdown -> CountdownTile(tile, m)
            is TileContent.Coin -> CoinTile(tile, m)
            is TileContent.Dice -> DiceTile(tile, m)
            is TileContent.Notes -> NotesTile(tile, m, open = false)
            else -> Unit
        }
    }

    private val sixDoses = TileContent.Routine(
        "evening blood pressure medicine", daily = true,
        times = listOf(6 * 60, 9 * 60 + 30, 12 * 60 + 45, 15 * 60, 18 * 60 + 15, 22 * 60 + 30), everyDays = 7,
        marks = listOf(at(21, 6), at(21, 10)) + (1..13).flatMap { d -> List(if (d % 4 == 0) 3 else 6) { h -> at(21 - d, 6 + h) } },
    )

    @Test
    fun routineSixDoses() = sizes("routine_six", sixDoses, "emoji:💊")

    @Test
    fun routineSixDosesLargeFont() = sizes("routine_six_font", sixDoses, "emoji:💊", fontScale = 1.5f)

    @Test
    fun routineLongOverdue() = sizes(
        "routine_overdue",
        TileContent.Routine("change the water filter cartridge", daily = false, times = listOf(480), everyDays = 30, marks = listOf(at(21, 9) - 150L * 86_400_000L)),
        "glyph:drop",
    )

    @Test
    fun routineNeverDone() = sizes("routine_never", TileContent.Routine("", daily = false, times = listOf(480), everyDays = 365))

    @Test
    fun countdownFar() = sizes("countdown_far", TileContent.Countdown("our wedding anniversary in portugal", LocalDate.of(2028, 3, 14).toEpochDay(), at(1, 9)))

    @Test
    fun countdownPast() = sizes("countdown_past", TileContent.Countdown("exam", LocalDate.of(2026, 11, 9).toEpochDay()))

    @Test
    fun countdownToday() = sizes("countdown_today", TileContent.Countdown("birthday", LocalDate.of(2026, 11, 21).toEpochDay()))

    @Test
    fun coin() {
        listOf(100L, 101L, 102L, 103L).forEach { ToolMemory.tosses[it] = ToolMemory.Toss(true, 3, List(10) { i -> i % 3 == 0 }) }
        sizes("coin", TileContent.Coin())
    }

    @Test
    fun diceThreeTwenty() {
        listOf(100L, 101L, 102L, 103L).forEach { ToolMemory.rolls[it] = ToolMemory.Roll(listOf(20, 17, 9), 1) }
        sizes("dice_d20", TileContent.Dice(3, 20))
    }

    @Test
    fun diceThreeSix() {
        listOf(100L, 101L, 102L, 103L).forEach { ToolMemory.rolls[it] = ToolMemory.Roll(listOf(6, 5, 4), 1) }
        sizes("dice_d6", TileContent.Dice(3, 6))
    }

    private val longList = TileContent.Notes(
        "everything for the weekend trip to the hills", NoteStyle.NOTE,
        listOf(
            "sunscreen with a very long description that never ends", "charger", "passport and the printed hotel booking",
            "water bottles", "snacks", "first aid kit", "rain jacket", "torch", "spare socks", "power bank", "maps", "camera",
        ).mapIndexed { i, t -> NoteItem(t, done = i % 4 == 3) },
    )

    @Test
    fun longList() = sizes("notes_list", longList)

    /** Text and a long checklist in one note. */
    @Test
    fun noteWithTextAndList() = sizes("notes_both", longList.copy(text = "Leave by 6. Fuel up on the way, the pumps after the toll are closed at night."))

    @Test
    fun longListLargeFont() = sizes("notes_list_font", longList, fontScale = 1.5f)

    @Test
    fun longText() = sizes(
        "notes_text",
        TileContent.Notes(
            "", NoteStyle.NOTE,
            text = "Call the plumber about the kitchen tap before Friday. The landlord's number is 98450 12345 and the " +
                "building manager is on 080 2222 3333. Ask about the bathroom fan too, it rattles at night and the " +
                "switch sparks sometimes. Also: renew the parking permit, pay the electricity bill, return the library books.",
        ),
    )

    @Test
    fun ink() {
        val lines = List(6) { row -> List(40) { i -> InkPoint(0.05f + i * 0.022f, 0.1f + row * 0.16f + (i % 5) * 0.01f, 0.6f) } }
        sizes("notes_ink", TileContent.Notes("sketch", NoteStyle.INK, ink = InkCodec.encode(lines)))
    }
}
