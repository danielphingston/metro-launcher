package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.Countdowns
import io.github.danielphingston.metro.data.InkCodec
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.NoteItem
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.RoutineTap
import io.github.danielphingston.metro.data.Routines
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.data.ToolCarry
import io.github.danielphingston.metro.data.ToolLimits
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.robolectric.RuntimeEnvironment
import io.github.danielphingston.metro.ui.start.FolderLayout
import io.github.danielphingston.metro.ui.start.NotesPanelSize
import io.github.danielphingston.metro.ui.start.Placement
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The tool tiles' rules: routines, countdowns, handwriting, how they are saved and how undo treats them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ToolTilesTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val twice = TileContent.Routine("vitamins", daily = true, times = listOf(8 * 60, 20 * 60), everyDays = 7)
    private val weekly = TileContent.Routine("plants", daily = false, times = listOf(8 * 60), everyDays = 7)

    @Test
    fun dailyDosesFallDueAtTheirTimesAndTickInOrder() {
        // Before the first dose: nothing due, the next one named.
        var s = Routines.status(twice, at(21, 7), zone, is24Hour = false)
        assertFalse(s.due)
        assertEquals("next 8 am", s.headline)
        // From its time the first is due.
        s = Routines.status(twice, at(21, 9), zone, false)
        assertTrue(s.due)
        assertEquals("due now", s.headline)
        // A tick, and nothing is due until the evening.
        val one = Routines.mark(twice, at(21, 9))
        s = Routines.status(one, at(21, 10), zone, false)
        assertFalse(s.due)
        assertEquals("next 8 pm", s.headline)
        assertEquals("1 of 2 today", s.detail)
        assertEquals(listOf(true, false), s.slots.map { it.done })
        // Both missed by the evening: two due.
        assertEquals("2 due", Routines.status(twice, at(21, 21), zone, false).headline)
        // Both done; the next day starts again.
        val both = Routines.mark(one, at(21, 21))
        assertTrue(Routines.status(both, at(21, 22), zone, false).complete)
        assertEquals("next 8 am", Routines.status(both, at(22, 7), zone, false).headline)
        assertEquals("next 08:00", Routines.status(both, at(22, 7), zone, true).headline)
    }

    @Test
    fun everyFewDaysCountsTheDaysSinceItWasLastDone() {
        assertEquals("not done yet", Routines.status(weekly, at(21, 9), zone, false).headline)
        val done = Routines.mark(weekly, at(14, 18))
        assertEquals("due today", Routines.status(done, at(21, 9), zone, false).headline)
        assertTrue(Routines.status(done, at(21, 9), zone, false).due)
        assertEquals("in 3 days", Routines.status(done, at(18, 9), zone, false).headline)
        assertEquals("tomorrow", Routines.status(done, at(20, 23), zone, false).headline)
        val late = Routines.status(done, at(23, 9), zone, false)
        assertEquals("2 days late", late.headline)
        assertEquals(2, late.late)
        val today = Routines.status(Routines.mark(done, at(23, 9)), at(23, 10), zone, false)
        assertEquals("done", today.headline)
        assertEquals("again in 7 days", today.detail)
        assertFalse(today.due)
    }

    @Test
    fun aSecondTapSoonAfterTakesTheTickBackAndNothingIsTickedTwice() {
        val now = at(21, 9)
        val first = RoutineTap.tap(twice, now, zone, lastMark = null) as RoutineTap.Outcome.Marked
        assertEquals(1, Routines.doneToday(first.routine, now, zone))
        // Within the window: taken back.
        val undone = RoutineTap.tap(first.routine, now + 3_000, zone, lastMark = first.at)
        assertTrue(undone is RoutineTap.Outcome.Unmarked)
        assertEquals(0, Routines.doneToday((undone as RoutineTap.Outcome.Unmarked).routine, now, zone))
        // After it: a tap ticks the next dose instead.
        val second = RoutineTap.tap(first.routine, now + 10_000, zone, lastMark = first.at)
        assertTrue(second is RoutineTap.Outcome.Marked)
        val all = (second as RoutineTap.Outcome.Marked).routine
        // Everything done: a tap changes nothing.
        assertTrue(RoutineTap.tap(all, now + 60_000, zone, lastMark = null) is RoutineTap.Outcome.Nothing)
        // Every few days: once a day.
        val w = (RoutineTap.tap(weekly, now, zone, null) as RoutineTap.Outcome.Marked).routine
        assertTrue(RoutineTap.tap(w, now + 60_000, zone, null) is RoutineTap.Outcome.Nothing)
    }

    @Test
    fun correctionsAndPruningKeepWhatMatters() {
        val now = at(21, 9)
        assertEquals(2, Routines.doneToday(Routines.setToday(twice, 2, now, zone), now, zone))
        val yesterday = Routines.setYesterday(weekly, true, now, zone)
        assertTrue(Routines.doneYesterday(yesterday, now, zone))
        assertFalse(Routines.doneYesterday(Routines.setYesterday(yesterday, false, now, zone), now, zone))
        // A mark from long ago goes, unless it is the newest one.
        val old = weekly.copy(marks = listOf(at(1, 9) - 60L * 86_400_000L))
        assertEquals(listOf(now), Routines.mark(old, now).marks)
        assertEquals(listOf(8 * 60, 12 * 60), Routines.addTime(listOf(12 * 60), 8 * 60))
        assertEquals(List(6) { it }, Routines.addTime(List(6) { it }, 100))
    }

    @Test
    fun routinesWakeOnlyWhenTheirFaceChanges() {
        assertEquals(at(21, 20), Routines.nextChange(twice, at(21, 9), zone))
        assertEquals(at(22, 0), Routines.nextChange(twice, at(21, 21), zone))
        assertEquals(at(22, 0), Routines.nextChange(weekly, at(21, 9), zone))
        assertEquals("12:30 pm", Routines.timeLabel(12 * 60 + 30, false))
        assertEquals("12 am", Routines.timeLabel(0, false))
    }

    @Test
    fun countdownsCountDownThenUp() {
        val day = LocalDate.of(2026, 12, 3).toEpochDay()
        assertEquals(12L, Countdowns.daysLeft(day, at(21, 9), zone))
        assertEquals("12" to "days to go", Countdowns.face(day, at(21, 9), zone, 0))
        assertEquals("1w 5d" to "to go", Countdowns.face(day, at(21, 9), zone, 1))
        assertEquals("3 dec" to "thu", Countdowns.face(day, at(21, 9), zone, 2))
        assertEquals("today" to "", Countdowns.face(LocalDate.of(2026, 11, 21).toEpochDay(), at(21, 9), zone, 0))
        assertEquals("3" to "days ago", Countdowns.face(LocalDate.of(2026, 11, 18).toEpochDay(), at(21, 9), zone, 0))
    }

    @Test
    fun handwritingRoundTripsAndDamagedInkIsEmpty() {
        val strokes = listOf(
            listOf(InkPoint(0.1f, 0.2f, 0.5f), InkPoint(0.4f, 0.25f, 1f), InkPoint(0.9f, 0.3f, 0f)),
            listOf(InkPoint(0.5f, 0.5f, 0.3f)),
        )
        val back = InkCodec.decode(InkCodec.encode(strokes))
        assertEquals(2, back.size)
        back.flatten().zip(strokes.flatten()).forEach { (a, b) ->
            assertEquals(b.x, a.x, 0.001f)
            assertEquals(b.y, a.y, 0.001f)
            assertEquals(b.pressure, a.pressure, 0.01f)
        }
        assertEquals(emptyList<List<InkPoint>>(), InkCodec.decode("not ink"))
        assertEquals("", InkCodec.encode(emptyList()))
        // Points closer than the threshold go (one in about 20 stays here); the ends stay.
        val dense = List(100) { InkPoint(it * 0.0001f, 0f, 0.5f) }
        val thin = InkCodec.simplify(dense)
        assertTrue(thin.size in 3..8)
        assertEquals(dense.first(), thin.first())
        assertEquals(dense.last(), thin.last())
        // A note keeps at most MAX_POINTS.
        val huge = List(5) { List(4_000) { i -> InkPoint(i / 4000f, 0.1f, 0.5f) } }
        assertTrue(InkCodec.decode(InkCodec.encode(huge)).sumOf { it.size } <= InkCodec.MAX_POINTS)
    }

    @Test
    fun toolTilesSurviveASaveAndLoad() {
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, twice.copy(marks = listOf(at(21, 9)), created = 5), 0, 0, icon = "emoji:💊"),
            TileSpec(2, TileSize.WIDE, TileContent.Countdown("goa", LocalDate.of(2026, 12, 3).toEpochDay(), 6), 0, 2),
            TileSpec(3, TileSize.SMALL, TileContent.Coin(7), 2, 0),
            TileSpec(4, TileSize.MEDIUM, TileContent.Dice(3, 20, 8), 0, 4),
            TileSpec(5, TileSize.WIDE, TileContent.Notes("groceries", NoteStyle.LIST, listOf(NoteItem("milk"), NoteItem("eggs", true)), "x", "", 9), 0, 6),
            TileSpec(6, TileSize.MEDIUM, TileContent.Notes("", NoteStyle.INK, ink = InkCodec.encode(listOf(listOf(InkPoint(0.5f, 0.5f, 0.5f)))), created = 10), 0, 8),
        )
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
        // Out-of-range values from a damaged file are brought back into range.
        val odd = """[{"id":1,"size":"SMALL","type":"dice","count":9,"sides":7},{"id":2,"size":"SMALL","type":"routine","times":[3000,60,60,30],"everyDays":0}]"""
        val read = TileJson.decode(JSONArray(odd))
        assertEquals(TileContent.Dice(3, 6), read[0].content)
        assertEquals(listOf(30, 60), (read[1].content as TileContent.Routine).times)
        assertEquals(1, (read[1].content as TileContent.Routine).everyDays)
    }

    @Test
    fun undoingALayoutEditKeepsTicksAndNotes() {
        val routine = TileSpec(1, TileSize.MEDIUM, twice, 0, 0)
        val notes = TileSpec(2, TileSize.MEDIUM, TileContent.Notes("n", NoteStyle.LIST), 2, 0)
        var live = listOf(routine, notes)
        val undo = TileUndo(current = { live }, restore = { live = it }, release = {})
        // A layout edit (the notes tile moves), then the tiles are used.
        undo.record("tile moved") { live = listOf(routine, notes.copy(x = 0, y = 2)) }
        live = listOf(
            routine.copy(content = twice.copy(marks = listOf(at(21, 9)))),
            notes.copy(x = 0, y = 2, content = TileContent.Notes("n", NoteStyle.LIST, listOf(NoteItem("milk")))),
        )
        undo.undo()
        // The move is taken back; the tick and the item stay.
        assertEquals(2, live[1].x)
        assertEquals(listOf(at(21, 9)), (live[0].content as TileContent.Routine).marks)
        assertEquals(listOf(NoteItem("milk")), (live[1].content as TileContent.Notes).items)
        // Nothing to carry: the restored layout as it was.
        assertEquals(listOf(routine), ToolCarry.carry(listOf(routine), emptyList()))
    }

    @Test
    fun anOpenNoteMovesTheTilesBelowOutOfItsWay() {
        val base = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 4, 2))
        val layout = FolderLayout.compute(base, openIndex = 0, childSizes = emptyList(), columns = 4, regionRows = 3)
        assertEquals(Placement(0, 2, 4, 3), layout.region)
        assertEquals(5, layout.tiles[2].y)
        assertNull(FolderLayout.compute(base, openIndex = 0, childSizes = emptyList(), columns = 4).region)
    }

    @Test
    fun switchingNoteStyleKeepsTheWords() {
        val list = TileContent.Notes("", NoteStyle.LIST, listOf(NoteItem("milk"), NoteItem("eggs", true)))
        val text = NotesPanelSize.switchStyle(list, NoteStyle.TEXT)
        assertEquals("milk\neggs", text.text)
        assertEquals(list.items, text.items)
        val back = NotesPanelSize.switchStyle(TileContent.Notes("", NoteStyle.TEXT, text = "a\n\n b "), NoteStyle.LIST)
        assertEquals(listOf(NoteItem("a"), NoteItem("b")), back.items)
        // Handwriting keeps its own content.
        assertEquals(NoteStyle.INK, NotesPanelSize.switchStyle(list, NoteStyle.INK).style)
        assertEquals(list.items, NotesPanelSize.switchStyle(list, NoteStyle.INK).items)
    }

    @Test
    fun everythingStoredHasACeiling() {
        val huge = TileContent.Notes(
            "n".repeat(80), NoteStyle.LIST,
            items = List(70) { NoteItem("x".repeat(150)) },
            text = "t".repeat(5_000),
        )
        val clean = ToolLimits.clean(huge) as TileContent.Notes
        assertEquals(ToolLimits.NAME, clean.name.length)
        assertEquals(ToolLimits.ITEMS, clean.items.size)
        assertTrue(clean.items.all { it.text.length == ToolLimits.ITEM })
        assertEquals(ToolLimits.TEXT, clean.text.length)
        val routine = ToolLimits.clean(twice.copy(name = "r".repeat(99), times = (0 until 10).map { it * 60 } + 5000, everyDays = 9_999)) as TileContent.Routine
        assertEquals(ToolLimits.NAME, routine.name.length)
        assertEquals(Routines.MAX_TIMES, routine.times.size)
        assertEquals(Routines.MAX_EVERY_DAYS, routine.everyDays)
        assertEquals(TileContent.Dice(3, 6), ToolLimits.clean(TileContent.Dice(9, 7)))
        // A layout from a file (a backup, another build) is held to the same limits.
        val json = JSONArray(TileJson.encode(listOf(TileSpec(1, TileSize.WIDE, huge, 0, 0))).toString())
        assertEquals(clean, TileJson.decode(json).single().content)
        // A long note switched to a list keeps only what a list may hold.
        val many = TileContent.Notes("", NoteStyle.TEXT, text = (1..80).joinToString("\n") { "line $it" })
        assertEquals(ToolLimits.ITEMS, NotesPanelSize.switchStyle(many, NoteStyle.LIST).items.size)
    }

    @Test
    fun theOpenBandStaysWithinAScreenWhateverItHolds() {
        val context = RuntimeEnvironment.getApplication()
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(2.75f), LayoutDirection.Ltr)
        fun rows(notes: TileContent.Notes, editing: Boolean) = NotesPanelSize.rows(notes, editing, 99.dp, 392.dp, measurer, Density(2.75f))
        val few = TileContent.Notes("", NoteStyle.LIST, List(3) { NoteItem("item $it") })
        val full = TileContent.Notes("", NoteStyle.LIST, List(ToolLimits.ITEMS) { NoteItem("item $it", done = it % 2 == 0) })
        assertTrue(rows(few, false) < rows(full, false))
        // Fifty items scroll inside the band: about four rows of start, five while editing.
        assertTrue(rows(full, false) <= 5)
        assertTrue(rows(full, true) <= 6)
        val long = TileContent.Notes("", NoteStyle.TEXT, text = "word ".repeat(400))
        assertTrue(rows(long, false) <= 5)
        assertTrue(rows(long, true) <= 5)
        assertTrue(rows(TileContent.Notes("", NoteStyle.INK), true) <= 7)
    }
}
