package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.InkStroke
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.NoteItem
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileOptions
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.ToolLabels
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.LiveTileEntry
import io.github.danielphingston.metro.ui.start.MetroTilesPanel
import io.github.danielphingston.metro.ui.start.NotesPanel
import io.github.danielphingston.metro.ui.start.TileCustomiseSheet
import io.github.danielphingston.metro.ui.start.ToolSettings
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.LocalInkSource
import io.github.danielphingston.metro.ui.tiles.LocalToolClock
import io.github.danielphingston.metro.ui.tiles.ToolFace
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The tool tiles at each size and in their states (due, late, done; ticked, empty), the open
 * notes band as it reads and while it is edited, the "metro tiles" panel and a routine's settings.
 * The clock and time zone are fixed, so every run draws the same faces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi", application = android.app.Application::class)
class ToolTilesScreenshotTest {
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

    private val zone get() = ZoneId.systemDefault()

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** Saturday 21 November 2026, 9 am. */
    private val now get() = at(21, 9)

    private class Item(val x: Int, val y: Int, val size: TileSize, val content: TileContent.Tool, val icon: String? = null)

    private fun capture(name: String, items: List<Item>) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        rule.setContent {
            CompositionLocalProvider(LocalToolClock provides { now }, LocalInkSource provides { inks[it].orEmpty() }) {
                MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                    Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 40.dp)) {
                            val gap = 4.dp
                            val c = (maxWidth + gap) / 4
                            items.forEachIndexed { i, item ->
                                val spec = TileSpec(i + 1L, item.size, item.content, icon = item.icon)
                                val tile = ResolvedTile(spec, null, ToolLabels.label(item.content))
                                Box(Modifier.offset(c * item.x, c * item.y).size(c * item.size.width - gap, c * item.size.height - gap)) {
                                    Face(tile, c, icons)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions)
    }

    @Composable
    private fun Face(tile: ResolvedTile, cell: androidx.compose.ui.unit.Dp, icons: IconCache) = ToolFace(tile, rememberTileMetrics(cell), icons, open = false)

    private fun routine(name: String, daily: Boolean, times: List<Int>, every: Int, marks: List<Long>) =
        TileContent.Routine(name, daily, times, every, marks)

    @Test
    fun routines() {
        val plantsLate = routine("water plants", false, listOf(480), 7, listOf(at(12, 18), at(5, 18)))
        val binsDue = routine("bins out", false, listOf(480), 7, listOf(at(14, 20)))
        val bedsheets = routine("bedsheets", false, listOf(480), 14, listOf(at(17, 10)))
        val vitamins = routine("vitamins", true, listOf(8 * 60, 20 * 60), 7, listOf(at(21, 8, 10)) + (1..9).flatMap { d -> listOf(at(21 - d, 8), at(21 - d, 20)).take(if (d == 3) 1 else 2) })
        val medicine = routine("medicine", true, listOf(7 * 60, 13 * 60, 19 * 60, 22 * 60), 7, listOf(at(21, 7, 5)))
        val doneToday = routine("walk", false, listOf(480), 1, listOf(at(21, 7)))
        capture(
            "tools_routine",
            listOf(
                Item(0, 0, TileSize.MEDIUM, plantsLate, "emoji:🌱"),
                Item(2, 0, TileSize.MEDIUM, vitamins, "emoji:💊"),
                Item(0, 2, TileSize.SMALL, plantsLate, "glyph:plant"),
                Item(1, 2, TileSize.SMALL, binsDue),
                Item(2, 2, TileSize.SMALL, vitamins, "emoji:💊"),
                Item(3, 2, TileSize.SMALL, doneToday),
                Item(0, 3, TileSize.WIDE, medicine),
                Item(0, 5, TileSize.MEDIUM, bedsheets, "glyph:check"),
                Item(2, 5, TileSize.MEDIUM, doneToday, "emoji:🏃"),
                Item(0, 7, TileSize.LARGE, vitamins, "emoji:💊"),
            ),
        )
    }

    @Test
    fun countdownCoinAndDice() {
        val trip = TileContent.Countdown("goa trip", LocalDate.of(2026, 12, 3).toEpochDay(), at(1, 9))
        val past = TileContent.Countdown("exam", LocalDate.of(2026, 11, 18).toEpochDay())
        ToolMemory.tosses[7] = ToolMemory.Toss(false, 6, listOf(true, false, false, true, true, false))
        ToolMemory.rolls[9] = ToolMemory.Roll(listOf(6, 2), 1)
        ToolMemory.rolls[10] = ToolMemory.Roll(listOf(3, 5, 6), 1)
        capture(
            "tools_countdown_coin_dice",
            listOf(
                Item(0, 0, TileSize.WIDE, trip),
                Item(0, 2, TileSize.MEDIUM, trip),
                Item(2, 2, TileSize.SMALL, trip),
                Item(3, 2, TileSize.SMALL, past),
                Item(2, 3, TileSize.SMALL, TileContent.Coin()),
                Item(3, 3, TileSize.SMALL, TileContent.Dice(1, 6)),
                Item(0, 4, TileSize.WIDE, TileContent.Coin()),
                Item(0, 6, TileSize.MEDIUM, TileContent.Dice(1, 20)),
                Item(2, 6, TileSize.MEDIUM, TileContent.Dice(2, 6)),
                Item(0, 8, TileSize.WIDE, TileContent.Dice(3, 6)),
            ),
        )
    }

    @Test
    fun notes() {
        val groceries = TileContent.Notes(
            "groceries", NoteStyle.NOTE,
            listOf(NoteItem("milk"), NoteItem("eggs"), NoteItem("coriander", true), NoteItem("bread"), NoteItem("dish soap"), NoteItem("rice")),
        )
        val allDone = groceries.copy(name = "packing", items = groceries.items.map { it.copy(done = true) })
        val note = TileContent.Notes("door", NoteStyle.NOTE, text = "gate code 4471#\nwifi: guest / tangerine22")
        val both = groceries.copy(name = "saturday", text = "bring the cooler bag; the shop closes at 9")
        val ink = TileContent.Notes("scribble", NoteStyle.INK, inkId = "scribble")
        capture(
            "tools_notes",
            listOf(
                Item(0, 0, TileSize.WIDE, groceries),
                Item(0, 2, TileSize.MEDIUM, groceries),
                Item(2, 2, TileSize.SMALL, groceries),
                Item(3, 2, TileSize.SMALL, allDone),
                Item(2, 3, TileSize.SMALL, note),
                Item(3, 3, TileSize.SMALL, TileContent.Notes("", NoteStyle.INK)),
                Item(0, 4, TileSize.MEDIUM, note),
                Item(2, 4, TileSize.MEDIUM, ink),
                Item(0, 6, TileSize.MEDIUM, allDone),
                Item(2, 6, TileSize.MEDIUM, TileContent.Notes("", NoteStyle.NOTE)),
                Item(0, 8, TileSize.LARGE, both),
                Item(0, 12, TileSize.WIDE, TileContent.Notes("colours", NoteStyle.INK, inkId = "colours", penOnTile = true)),
            ),
        )
    }

    /** The drawings the tiles here show, by id: a white scribble and the same in colour. */
    private val inks by lazy {
        mapOf(
            "scribble" to scribble().map { InkStroke(it) },
            "colours" to scribble().mapIndexed { i, s -> InkStroke(s, if (i == 0) 0xFFE53935.toInt() else 0xFFFDD835.toInt()) },
        )
    }

    /** A loop and a wave, as a finger or pen would draw them. */
    private fun scribble(): List<List<InkPoint>> {
        val loop = List(60) { i ->
            val a = i / 59f * 2 * PI
            InkPoint((0.3f + 0.15f * cos(a)).toFloat(), (0.3f + 0.12f * sin(a)).toFloat(), 0.3f + 0.6f * (i / 59f))
        }
        val wave = List(80) { i -> InkPoint(0.1f + i / 100f, (0.7f + 0.06f * sin(i / 6.0)).toFloat(), 0.8f) }
        return listOf(loop, wave)
    }

    private fun panel(name: String, editing: Boolean, notes: TileContent.Notes) {
        rule.setContent {
            CompositionLocalProvider(LocalInkSource provides { inks[it].orEmpty() }) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    Column(Modifier.padding(top = 40.dp).fillMaxWidth().background(Metro.colors.chrome.copy(alpha = 0.92f)).padding(horizontal = 8.dp)) {
                        NotesPanel(
                            notes = notes, label = ToolLabels.label(notes), draft = if (editing) notes else null,
                            onTick = {}, onEdit = {}, onDraft = {}, onDone = {}, onCancel = {},
                        )
                    }
                }
            }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions)
    }

    private val list = TileContent.Notes(
        "groceries", NoteStyle.NOTE, text = "the shop near the station closes at 9",
        items = listOf(NoteItem("milk"), NoteItem("eggs"), NoteItem("coriander", true), NoteItem("bread")),
    )

    @Test
    fun notesBandReading() = panel("tools_notes_band", editing = false, notes = list)

    @Test
    fun notesBandEditing() = panel("tools_notes_band_edit", editing = true, notes = list)

    private val fullList = TileContent.Notes(
        "packing", NoteStyle.NOTE,
        List(io.github.danielphingston.metro.data.ToolLimits.ITEMS) { NoteItem("item ${it + 1}" + if (it == 0) " with a long name that takes two lines in the open band" else "", done = it % 5 == 4) },
    )

    /** Fifty items: the band keeps to a few rows and scrolls inside. */
    @Test
    fun longListBandReading() = panel("tools_notes_band_long", editing = false, notes = fullList)

    /** Editing fifty items: the list scrolls, the field says it is full, cancel and done stay in view. */
    @Test
    fun longListBandEditing() = panel("tools_notes_band_long_edit", editing = true, notes = fullList)

    @Test
    fun handwritingBandEditing() = panel("tools_ink_band_edit", editing = true, notes = TileContent.Notes("scribble", NoteStyle.INK, inkId = "colours", penColor = 0xFFE53935.toInt()))

    /** The handwriting tile's pen section: colours, and "write on the tile" greyed on a medium tile. */
    @Test
    fun inkSettings() {
        val spec = TileSpec(1, TileSize.MEDIUM, TileContent.Notes("scribble", NoteStyle.INK, inkId = "scribble"), 0, 0)
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    TileCustomiseSheet(
                        spec = spec, options = TileOptions.available(spec), startTransparency = 0,
                        onShowName = {}, onShowIcon = {}, onIconSource = {}, onPhoto = {}, onBackground = {},
                        onColour = {}, onTransparency = {}, onWidgetSettings = null, onDismiss = {},
                        tool = { ToolSettings(spec, onChange = {}, onData = {}, onChooseIcon = {}, onClearIcon = {}) },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/customise_handwriting.png", roborazziOptions = ScreenshotOptions)
    }

    @Test
    fun metroTilesPanel() {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val live = listOf("weather" to TileContent.Weather, "photos" to TileContent.Photos, "people" to TileContent.People, "me" to TileContent.Me)
            .map { (label, content) -> LiveTileEntry(label, content, TileSize.WIDE) }
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    MetroTilesPanel(live, icons, onAddLive = {}, onAddTool = {}, onDismiss = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/metro_tiles_menu.png", roborazziOptions = ScreenshotOptions)
    }

    @Test
    fun routineSettings() {
        val spec = TileSpec(1, TileSize.MEDIUM, TileContent.Routine("vitamins", true, listOf(8 * 60, 20 * 60), 7, listOf(now)), 0, 0)
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    CompositionLocalProvider(LocalToolClock provides { now }) { TileCustomiseSheet(
                        spec = spec, options = TileOptions.available(spec), startTransparency = 0,
                        onShowName = {}, onShowIcon = {}, onIconSource = {}, onPhoto = {}, onBackground = {},
                        onColour = {}, onTransparency = {}, onWidgetSettings = null, onDismiss = {},
                        tool = { ToolSettings(spec, onChange = {}, onData = {}, onChooseIcon = {}, onClearIcon = {}) },
                    ) }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/customise_routine.png", roborazziOptions = ScreenshotOptions)
    }
}
