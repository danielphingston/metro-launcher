package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.StartEdit
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.ui.start.NoticeEvent
import io.github.danielphingston.metro.ui.start.NoticeState
import io.github.danielphingston.metro.ui.start.StartNotice
import io.github.danielphingston.metro.ui.start.StartNotices
import io.github.danielphingston.metro.ui.start.editUndoDescription
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class StartNoticesTest {
    @Test
    fun onlyEditsThatRemoveSomethingOfferTheBar() {
        val destructive = StartEdit.entries.filter { it.offersUndoBar() }
        assertEquals(listOf(StartEdit.UNPIN), destructive)
        assertTrue(StartEdit.FOLDER_REMOVED_FROM.offersUndoBar(dissolvedFolder = true))
        assertFalse(StartEdit.FOLDER_REMOVED_FROM.offersUndoBar(dissolvedFolder = false))
        assertTrue(StartEdit.UNPIN.offersUndoBar(dissolvedFolder = true))
    }

    @Test
    fun aDestructiveEditShowsTheBarAndAHarmlessOneHidesIt() {
        val shown = NoticeState().reduce(NoticeEvent.Edit("phone unpinned", destructive = true))
        assertEquals(StartNotice.Edited("phone unpinned", 1), shown.current)
        assertNull(NoticeState().reduce(NoticeEvent.Edit("tile moved", destructive = false)).current)
        // A harmless edit takes the older bar away: its undo would no longer be the latest step.
        assertNull(shown.reduce(NoticeEvent.Edit("tile moved", destructive = false)).current)
    }

    @Test
    fun aNewEditReplacesTheBarAndRestartsItsTimer() {
        val first = NoticeState().reduce(NoticeEvent.Edit("a unpinned", true))
        val second = first.reduce(NoticeEvent.Edit("a unpinned", true))
        assertEquals("a unpinned", second.current?.message)
        assertTrue(second.current!!.serial > first.current!!.serial)
    }

    @Test
    fun layoutSwitchesShowTheBackBar() {
        val s = NoticeState().reduce(NoticeEvent.Switched("work", previous = 7))
        assertEquals(StartNotice.Switched("work", 7, 1), s.current)
        assertEquals("back", s.current!!.action)
    }

    @Test
    fun touchScrollPageLeavingTimeoutAndUndoAllHideIt() {
        val hiding = listOf(
            NoticeEvent.Touch, NoticeEvent.Scroll, NoticeEvent.PageChanged,
            NoticeEvent.Left, NoticeEvent.Timeout, NoticeEvent.UndoTapped,
        )
        val bars = listOf(
            NoticeState().reduce(NoticeEvent.Edit("x unpinned", true)),
            NoticeState().reduce(NoticeEvent.Switched("work", 1)),
        )
        for (bar in bars) for (event in hiding) {
            assertNull("$event should hide ${bar.current}", bar.reduce(event).current)
        }
    }

    @Test
    fun hidingKeepsTheSerialSoTheNextBarStillRestartsItsTimer() {
        val hidden = NoticeState().reduce(NoticeEvent.Edit("a", true)).reduce(NoticeEvent.Touch)
        assertEquals(1L, hidden.serial)
        assertEquals(2L, hidden.reduce(NoticeEvent.Edit("a", true)).current?.serial)
    }

    @Test
    fun theBriefTimeIsFourSeconds() {
        assertEquals(4_000L, StartNotices.NOTICE_MILLIS)
    }

    @Test
    fun theEditModeButtonFollowsWhatTheNextUndoTakesBack() {
        assertNull(editUndoDescription(null))
        assertEquals("undo tile moved", editUndoDescription("tile moved"))

        var tiles: List<TileSpec>? = emptyList()
        val undo = TileUndo(current = { tiles }, restore = { tiles = it }, release = {})
        assertNull(editUndoDescription(undo.next.value))
        undo.record("tile moved") { tiles = listOf(TileSpec(1, io.github.danielphingston.metro.data.TileSize.SMALL, io.github.danielphingston.metro.data.TileContent.Weather, 0, 0)) }
        assertEquals("undo tile moved", editUndoDescription(undo.next.value))
        undo.undo()
        assertNull(editUndoDescription(undo.next.value))
    }

    @Test
    fun barReservesRoomOnlyWhenSomethingCoversTheBottom() {
        val notices = StartNotices()
        assertFalse(notices.barShown(editing = false, undoNext = "tile moved"))
        assertFalse(notices.barShown(editing = true, undoNext = null))
        assertTrue(notices.barShown(editing = true, undoNext = "tile moved"))
        notices.send(NoticeEvent.Edit("x unpinned", true))
        assertTrue(notices.barShown(editing = false, undoNext = null))
    }
}
