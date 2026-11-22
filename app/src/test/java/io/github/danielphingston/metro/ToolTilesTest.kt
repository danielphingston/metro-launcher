package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.Countdowns
import io.github.danielphingston.metro.data.InkCodec
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.InkStore
import io.github.danielphingston.metro.data.InkStroke
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
    fun aTapTicksAndADoubleTapTakesBackTodaysLastTick() {
        val now = at(21, 9)
        val first = RoutineTap.tap(twice, now, zone) as RoutineTap.Outcome.Changed
        assertEquals(1, Routines.doneToday(first.routine, now, zone))
        assertEquals("done · double-tap to undo", first.hint)
        // A double tap later still takes it back.
        val undone = RoutineTap.undo(first.routine, now + 3_600_000, zone) as RoutineTap.Outcome.Changed
        assertEquals(0, Routines.doneToday(undone.routine, now, zone))
        assertTrue(RoutineTap.undo(undone.routine, now, zone) is RoutineTap.Outcome.Nothing)
        // Both doses ticked: a tap changes nothing, a double tap takes back only the later one.
        val second = (RoutineTap.tap(first.routine, now + 10_000, zone) as RoutineTap.Outcome.Changed).routine
        assertTrue(RoutineTap.tap(second, now + 60_000, zone) is RoutineTap.Outcome.Nothing)
        assertEquals(listOf(now), (RoutineTap.undo(second, now + 60_000, zone) as RoutineTap.Outcome.Changed).routine.marks)
        // Yesterday's tick is not today's to take back: that is the settings' job.
        val yesterday = Routines.mark(weekly, at(20, 9))
        assertTrue(RoutineTap.undo(yesterday, now, zone) is RoutineTap.Outcome.Nothing)
        // Every few days: once a day.
        val w = (RoutineTap.tap(weekly, now, zone) as RoutineTap.Outcome.Changed).routine
        assertTrue(RoutineTap.tap(w, now + 60_000, zone) is RoutineTap.Outcome.Nothing)
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
    fun handwritingRoundTripsWithColoursAndCompresses() {
        val strokes = listOf(
            InkStroke(listOf(InkPoint(0.1f, 0.2f, 0.5f), InkPoint(0.4f, 0.25f, 1f), InkPoint(0.9f, 0.3f, 0f))),
            InkStroke(listOf(InkPoint(0.5f, 0.5f, 0.3f)), color = 0xFFE53935.toInt()),
        )
        val back = InkCodec.decode(InkCodec.encode(strokes))
        assertEquals(listOf(InkStroke.PLAIN, 0xFFE53935.toInt()), back.map { it.color })
        back.flatMap { it.points }.zip(strokes.flatMap { it.points }).forEach { (a, b) ->
            assertEquals(b.x, a.x, 0.001f)
            assertEquals(b.y, a.y, 0.001f)
            assertEquals(b.pressure, a.pressure, 0.01f)
        }
        // Damaged bytes, truncated bytes and nothing at all read as no drawing.
        assertEquals(emptyList<InkStroke>(), InkCodec.decode("not ink".toByteArray()))
        assertEquals(emptyList<InkStroke>(), InkCodec.decode(InkCodec.encode(strokes).copyOf(5)))
        assertEquals(0, InkCodec.encode(emptyList()).size)
        assertTrue(InkCodec.isReadable(InkCodec.encode(strokes)))
        assertFalse(InkCodec.isReadable("not ink".toByteArray()))
        // A page of handwriting, all points: what it costs, compressed, with a colour per stroke.
        val page = List(120) { s -> InkStroke(List(100) { i -> InkPoint(0.05f + i * 0.009f, 0.05f + s * 0.0075f + (i % 7) * 0.002f, 0.4f + (i % 5) * 0.1f) }, color = s * 77_777) }
        val bytes = InkCodec.encode(page)
        assertEquals(InkCodec.MAX_POINTS, InkCodec.decode(bytes).sumOf { it.points.size })
        val raw = 1 + page.sumOf { 6 + it.points.size * 5 }
        assertTrue("compressed ${bytes.size} of $raw", bytes.size < raw * 0.75)
        assertTrue("a full drawing stays under 40 KB, is ${bytes.size}", bytes.size < 40 * 1024)
        // Points closer than the threshold go (one in about 20 stays here); the ends stay.
        val dense = List(100) { InkPoint(it * 0.0001f, 0f, 0.5f) }
        val thin = InkCodec.simplify(dense)
        assertTrue(thin.size in 3..8)
        assertEquals(dense.first(), thin.first())
        assertEquals(dense.last(), thin.last())
        // More than MAX_POINTS: whole strokes past the limit are dropped.
        val huge = List(5) { InkStroke(List(4_000) { i -> InkPoint(i / 4000f, 0.1f, 0.5f) }) }
        assertTrue(InkCodec.decode(InkCodec.encode(huge)).sumOf { it.points.size } <= InkCodec.MAX_POINTS)
    }

    @Test
    fun drawingsLiveInTheirOwnFilesAndUnusedOnesGo() {
        val dir = java.nio.file.Files.createTempDirectory("ink").toFile()
        val store = InkStore(dir)
        val one = listOf(InkStroke(listOf(InkPoint(0.1f, 0.1f, 0.5f), InkPoint(0.2f, 0.2f, 0.5f)), color = 0xFF1E88E5.toInt()))
        store.write("ink-1", one)
        store.write("ink-2", one)
        assertEquals(one.map { it.color }, InkStore(dir).read("ink-1").map { it.color })
        // Ids that could reach outside the folder are refused.
        store.write("../evil", one)
        assertFalse(java.io.File(dir.parentFile, "evil").exists())
        assertEquals(emptyList<InkStroke>(), store.read("../evil"))
        // Only drawings nothing names go; a drawing written moments ago is kept regardless.
        assertEquals(1, store.deleteAllBut(setOf("ink-1"), keepSince = Long.MAX_VALUE))
        assertTrue(java.io.File(dir, "ink-1").exists())
        store.write("ink-3", one)
        assertEquals(0, store.deleteAllBut(emptySet(), keepSince = 0L))
        assertTrue(java.io.File(dir, "ink-3").exists())
        // Erasing everything removes the file.
        store.write("ink-1", emptyList())
        assertFalse(java.io.File(dir, "ink-1").exists())
        assertEquals(setOf("a-1", "b_2"), InkStore.referencedIn(listOf("""[{"inkId":"a-1"},{"x":1,"inkId" : "b_2"}]""", """{"inkId":"../no"}""")))
        dir.deleteRecursively()
    }

    @Test
    fun inkKeptInsideAnOldLayoutMovesToAFileOnce() {
        val dir = java.nio.file.Files.createTempDirectory("ink").toFile()
        val store = InkStore(dir)
        // An earlier preview build kept version-1 ink inside the tile, as Base64.
        val v1 = java.nio.ByteBuffer.allocate(1 + 2 + 2 * 5).apply {
            put(1.toByte())
            putShort(2.toShort())
            putShort(400.toShort()); putShort(400.toShort()); put(100.toByte())
            putShort(800.toShort()); putShort(800.toShort()); put(200.toByte())
        }.array()
        val old = """[{"id":7,"size":"MEDIUM","type":"notes","name":"","style":"ink","ink":"${java.util.Base64.getEncoder().encodeToString(v1)}","created":5}]"""
        val notes = TileJson.decode(JSONArray(old)).single().content as TileContent.Notes
        assertEquals("legacy-7-5", notes.inkId)
        assertEquals(1, store.read(notes.inkId).size)
        assertTrue(java.io.File(dir, notes.inkId).exists())
        // Written over since: the old copy in the layout, read again at the next start, never wins.
        store.write(notes.inkId, emptyList())
        store.write(notes.inkId, List(3) { InkStroke(listOf(InkPoint(0.5f, 0.5f, 0.5f))) })
        TileJson.decode(JSONArray(old))
        assertEquals(3, InkStore(dir).read(notes.inkId).size)
        dir.deleteRecursively()
    }

    @Test
    fun toolTilesSurviveASaveAndLoad() {
        val tiles = listOf(
            TileSpec(1, TileSize.MEDIUM, twice.copy(marks = listOf(at(21, 9)), created = 5), 0, 0, icon = "emoji:💊"),
            TileSpec(2, TileSize.WIDE, TileContent.Countdown("goa", LocalDate.of(2026, 12, 3).toEpochDay(), 6), 0, 2),
            TileSpec(3, TileSize.SMALL, TileContent.Coin(7), 2, 0),
            TileSpec(4, TileSize.MEDIUM, TileContent.Dice(3, 20, 8), 0, 4),
            TileSpec(5, TileSize.WIDE, TileContent.Notes("groceries", NoteStyle.NOTE, listOf(NoteItem("milk"), NoteItem("eggs", true)), "x", "", 9), 0, 6),
            TileSpec(6, TileSize.WIDE, TileContent.Notes("", NoteStyle.INK, inkId = "ink-10-1", penColor = 0xFFE53935.toInt(), penOnTile = true, created = 10), 0, 8),
            TileSpec(7, TileSize.MEDIUM, TileContent.Notes("", NoteStyle.INK, inkId = "ink-11-1", created = 11), 0, 10),
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
        val notes = TileSpec(2, TileSize.MEDIUM, TileContent.Notes("n", NoteStyle.NOTE), 2, 0)
        var live = listOf(routine, notes)
        val undo = TileUndo(current = { live }, restore = { live = it }, release = {})
        // A layout edit (the notes tile moves), then the tiles are used.
        undo.record("tile moved") { live = listOf(routine, notes.copy(x = 0, y = 2)) }
        live = listOf(
            routine.copy(content = twice.copy(marks = listOf(at(21, 9)))),
            notes.copy(x = 0, y = 2, content = TileContent.Notes("n", NoteStyle.NOTE, listOf(NoteItem("milk")))),
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
    fun noteKindsAreSavedAndOldIdsReadAsNotes() {
        assertEquals(NoteStyle.NOTE, NoteStyle.fromId("note"))
        assertEquals(NoteStyle.INK, NoteStyle.fromId("ink"))
        // Ids from earlier builds of this branch (a checklist, a text note) are notes.
        assertEquals(NoteStyle.NOTE, NoteStyle.fromId("list"))
        assertEquals(NoteStyle.NOTE, NoteStyle.fromId("text"))
    }

    @Test
    fun everythingStoredHasACeiling() {
        val huge = TileContent.Notes(
            "n".repeat(80), NoteStyle.NOTE,
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
    }

    @Test
    fun theOpenBandStaysWithinAScreenWhateverItHolds() {
        val context = RuntimeEnvironment.getApplication()
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(2.75f), LayoutDirection.Ltr)
        fun rows(notes: TileContent.Notes, editing: Boolean) = NotesPanelSize.rows(notes, editing, 99.dp, 392.dp, measurer, Density(2.75f))
        val few = TileContent.Notes("", NoteStyle.NOTE, List(3) { NoteItem("item $it") })
        val full = TileContent.Notes("", NoteStyle.NOTE, List(ToolLimits.ITEMS) { NoteItem("item $it", done = it % 2 == 0) })
        assertTrue(rows(few, false) < rows(full, false))
        // Fifty items scroll inside the band: about four rows of start, five while editing.
        assertTrue(rows(full, false) <= 5)
        assertTrue(rows(full, true) <= 6)
        val long = TileContent.Notes("", NoteStyle.NOTE, text = "word ".repeat(400))
        assertTrue(rows(long, false) <= 5)
        assertTrue(rows(long, true) <= 5)
        // Text and fifty items together: still within about a screen, read or edited.
        assertTrue(rows(full.copy(text = "word ".repeat(400)), false) <= 5)
        assertTrue(rows(full.copy(text = "word ".repeat(400)), true) <= 7)
        assertTrue(rows(TileContent.Notes("", NoteStyle.INK), true) <= 7)
    }

    @Test
    fun aReadRacingAWriteNeverBringsBackTheOlderDrawing() {
        val dir = java.nio.file.Files.createTempDirectory("ink").toFile()
        val store = InkStore(dir)
        val id = "ink-race-1"
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        // Strokes added one at a time, each saved, while other threads keep reading (as the faces
        // on start do after every save).
        val strokes = ArrayList<InkStroke>()
        val readers = List(4) {
            pool.submit {
                repeat(400) { store.read(id) }
            }
        }
        repeat(60) { i ->
            strokes += InkStroke(listOf(InkPoint(i / 100f, 0.1f, 0.5f), InkPoint(i / 100f, 0.2f, 0.5f)))
            store.write(id, strokes.toList())
        }
        readers.forEach { it.get() }
        pool.shutdown()
        assertEquals(60, store.read(id).size)
        assertEquals(60, InkStore(dir).read(id).size)
        dir.deleteRecursively()
    }

    @Test
    fun handwritingSavedWithoutAnIdGetsAStableOne() {
        // A handwriting tile from a build before drawings had files: no "inkId", no ink.
        val old = """[{"id":9,"size":"WIDE","type":"notes","name":"","style":"ink","ink":"","created":12}]"""
        val first = TileJson.decode(JSONArray(old)).single().content as TileContent.Notes
        assertEquals("legacy-9-12", first.inkId)
        assertEquals(first.inkId, (TileJson.decode(JSONArray(old)).single().content as TileContent.Notes).inkId)
        // A note has no drawing, so no id.
        val note = """[{"id":3,"size":"WIDE","type":"notes","name":"","style":"note","created":1}]"""
        assertEquals("", (TileJson.decode(JSONArray(note)).single().content as TileContent.Notes).inkId)
    }
}
