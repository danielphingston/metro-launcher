package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.StartEdit
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.ui.start.NoticeEvent
import io.github.danielphingston.metro.ui.start.NoticeState
import io.github.danielphingston.metro.ui.start.StartNotice
import io.github.danielphingston.metro.ui.start.StartNotices
import io.github.danielphingston.metro.ui.start.editUndoDescription
import io.github.danielphingston.metro.ui.start.pillContent
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
    fun everyEditShowsThePill() {
        val shown = NoticeState().reduce(NoticeEvent.Edit("tile moved"))
        assertEquals(StartNotice.Edited("tile moved", 1), shown.current)
        assertEquals("phone unpinned", shown.reduce(NoticeEvent.Edit("phone unpinned")).current?.message)
    }

    @Test
    fun aNewEditReplacesThePillAndRestartsItsTimer() {
        val first = NoticeState().reduce(NoticeEvent.Edit("a unpinned"))
        val second = first.reduce(NoticeEvent.Edit("a unpinned"))
        assertEquals("a unpinned", second.current?.message)
        assertTrue(second.current!!.serial > first.current!!.serial)
    }

    @Test
    fun layoutSwitchesShowTheBackPill() {
        val s = NoticeState().reduce(NoticeEvent.Switched("work", previous = 7))
        assertEquals(StartNotice.Switched("work", 7, 1), s.current)
        assertEquals("back", s.current!!.action)
    }

    @Test
    fun leavingEditModeOffersTheLatestStepOnceMore() {
        val hidden = NoticeState().reduce(NoticeEvent.Edit("tile moved")).reduce(NoticeEvent.Timeout)
        assertNull(hidden.current)
        val again = hidden.reduce(NoticeEvent.EditModeLeft("tile moved"))
        assertEquals("tile moved", again.current?.message)
        assertTrue(again.current!!.serial > 1)
        // Nothing to undo: leaving edit mode shows nothing.
        assertNull(NoticeState().reduce(NoticeEvent.EditModeLeft(null)).current)
    }

    @Test
    fun undoKeepsThePillWhileStepsAreLeft() {
        val shown = NoticeState().reduce(NoticeEvent.Edit("tile resized"))
        val more = shown.reduce(NoticeEvent.UndoTapped("tile moved"))
        assertEquals("tile moved", more.current?.message)
        assertTrue(more.current!!.serial > shown.current!!.serial)
        assertNull(more.reduce(NoticeEvent.UndoTapped(null)).current)
    }

    @Test
    fun timeoutPageAndLeavingHideIt() {
        val hiding = listOf(NoticeEvent.PageChanged, NoticeEvent.Left, NoticeEvent.Timeout)
        val pills = listOf(
            NoticeState().reduce(NoticeEvent.Edit("x unpinned")),
            NoticeState().reduce(NoticeEvent.Switched("work", 1)),
        )
        for (pill in pills) for (event in hiding) {
            assertNull("$event should hide ${pill.current}", pill.reduce(event).current)
        }
    }

    @Test
    fun stepsGoOnceThePillIsGoneOutsideEditModeOnly() {
        assertTrue(NoticeState.clearsUndoWhenHidden(editing = false))
        assertFalse(NoticeState.clearsUndoWhenHidden(editing = true))
    }

    @Test
    fun hidingKeepsTheSerialSoTheNextPillStillRestartsItsTimer() {
        val hidden = NoticeState().reduce(NoticeEvent.Edit("a")).reduce(NoticeEvent.Timeout)
        assertEquals(1L, hidden.serial)
        assertEquals(2L, hidden.reduce(NoticeEvent.Edit("a")).current?.serial)
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
    fun inEditModeThePillStaysWhileSomethingCanBeUndone() {
        assertEquals("undo tile moved", pillContent(null, editing = true, undoNext = "tile moved")?.description)
        assertNull(pillContent(null, editing = true, undoNext = null))
        // Outside edit mode only a notice brings it up.
        assertNull(pillContent(null, editing = false, undoNext = "tile moved"))
        assertEquals("undo", pillContent(StartNotice.Edited("tile moved", 1), editing = false, undoNext = "tile moved")?.label)
        // Its step already gone: nothing to offer.
        assertNull(pillContent(StartNotice.Edited("tile moved", 1), editing = false, undoNext = null))
        // A layout switch shows no pill.
        assertNull(pillContent(StartNotice.Switched("work", 3, 1), editing = false, undoNext = null))
    }
}
