package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.StartEdit
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileFolders
import io.github.danielphingston.metro.data.TileReferences
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStyle
import io.github.danielphingston.metro.data.TileStyles
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.data.UndoStack
import io.github.danielphingston.metro.ui.start.GridPos
import io.github.danielphingston.metro.ui.start.TilePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileUndoTest {
    private fun app(id: Long, pkg: String, x: Int, y: Int, size: TileSize = TileSize.MEDIUM) =
        TileSpec(id, size, TileContent.App(AppKey(pkg, "$pkg.Main", 0)), x, y)

    private fun widget(id: Long, widgetId: Int, x: Int, y: Int) =
        TileSpec(id, TileSize.MEDIUM, TileContent.Widget(widgetId, "com.example/.W", 0L), x, y)

    private fun shortcut(id: Long, shortcutId: String, label: String = "s") =
        TileSpec(id, TileSize.SMALL, TileContent.Shortcut("com.example", shortcutId, 0L, label))

    /** A stand-in for TileRepository: the live layout, edited the way the repository does it. */
    private class Live(var tiles: List<TileSpec>?) {
        val released = mutableListOf<TileSpec>()
        var otherLayouts: List<List<TileSpec>> = emptyList()
        val undo = TileUndo(
            current = { tiles },
            restore = { tiles = it },
            alsoHeld = { otherLayouts },
            release = { released += it },
        )

        fun edit(label: String = "edit", transform: (List<TileSpec>) -> List<TileSpec>) =
            undo.record(label) { tiles = TilePlacement.settle(transform(tiles!!), 4) }

        fun unpin(id: Long) {
            var removed: TileSpec? = null
            undo.record(StartEdit.UNPIN.label("x")) {
                removed = tiles!!.flatMap { listOf(it) + ((it.content as? TileContent.Folder)?.children.orEmpty()) }.first { it.id == id }
                tiles = TilePlacement.settle(TileFolders.remove(tiles!!, id), 4)
            }
            undo.releaseUnreferenced(listOf(removed!!))
        }
    }

    @Test
    fun stackPushPopAndCap() {
        val stack = UndoStack<Int>(3)
        assertNull(stack.pop())
        assertEquals(emptyList<Int>(), stack.push(1))
        stack.push(2)
        stack.push(3)
        assertEquals(listOf(1), stack.push(4))
        assertEquals(listOf(2), stack.push(5))
        assertEquals(listOf(3), stack.push(6))
        assertEquals(6, stack.pop())
        assertEquals(5, stack.peek())
        assertEquals(listOf(4, 5), stack.clear())
        assertEquals(0, stack.size)
    }

    @Test
    fun undoRestoresTheExactLayoutIncludingPositionsAndFolderChildren() {
        val start = TilePlacement.settle(
            listOf(app(1, "a", 0, 0), app(2, "b", 2, 0), app(3, "c", 0, 2), app(4, "d", 2, 2, TileSize.SMALL)),
            4,
        )
        val live = Live(start)
        live.edit { TileFolders.groupIntoFolder(it, 1, listOf(2, 3), 10)!!.tiles }
        val grouped = live.tiles
        live.edit { TilePlacement.moveTo(it, 4, GridPos(0, 4), 4) }
        live.edit { TileStyles.set(it, 2, TileStyle.NAME_ONLY, null) }
        live.unpin(4)
        assertFalse(live.tiles!!.any { it.id == 4L })

        live.undo.undo() // unpin
        live.undo.undo() // style
        live.undo.undo() // move
        assertEquals(grouped, live.tiles)
        live.undo.undo() // folder
        assertEquals(start, live.tiles)
        assertNull(live.undo.undo())
        assertNull(live.undo.next.value)
    }

    @Test
    fun anEditThatChangesNothingIsNotAStep() {
        val live = Live(listOf(app(1, "a", 0, 0)))
        assertFalse(live.edit { it })
        assertNull(live.undo.next.value)
        assertTrue(live.edit("tile moved") { TilePlacement.moveTo(it, 1, GridPos(2, 0), 4) })
        assertEquals("tile moved", live.undo.next.value)
    }

    @Test
    fun theStackKeepsTwentySteps() {
        val live = Live(listOf(app(1, "a", 0, 0, TileSize.SMALL)))
        repeat(25) { i -> live.edit("move $i") { TilePlacement.moveTo(it, 1, GridPos(i % 2 + 1, 0), 4) } }
        var undone = 0
        while (live.undo.undo() != null) undone++
        assertEquals(TileUndo.CAPACITY, undone)
    }

    @Test
    fun anUnpinnedWidgetKeepsItsIdWhileItCanBeUndone() {
        val live = Live(listOf(widget(1, 77, 0, 0), app(2, "a", 2, 0)))
        live.unpin(1)
        assertEquals(emptyList<TileSpec>(), live.released)
        live.undo.undo()
        assertTrue(live.tiles!!.any { (it.content as? TileContent.Widget)?.appWidgetId == 77 })
        assertEquals(emptyList<TileSpec>(), live.released)
    }

    @Test
    fun theWidgetIdIsFreedWhenItsStepFallsOffTheStack() {
        val live = Live(listOf(widget(1, 77, 0, 0), app(2, "a", 2, 0, TileSize.SMALL)))
        live.unpin(1)
        repeat(TileUndo.CAPACITY - 1) { i -> live.edit { TilePlacement.moveTo(it, 2, GridPos(i % 2 + 1, 0), 4) } }
        assertEquals(emptyList<TileSpec>(), live.released)
        live.edit { TilePlacement.moveTo(it, 2, GridPos(3, 1), 4) }
        assertEquals(listOf(77), live.released.map { (it.content as TileContent.Widget).appWidgetId })
    }

    @Test
    fun clearingTheStackFreesOnlyWhatNothingElseHolds() {
        val live = Live(listOf(widget(1, 77, 0, 0), widget(2, 78, 2, 0), shortcut(3, "s1")))
        // Another saved layout still has widget 78.
        live.otherLayouts = listOf(listOf(widget(9, 78, 0, 0)))
        live.unpin(1)
        live.unpin(2)
        live.unpin(3)
        assertEquals(emptyList<TileSpec>(), live.released)
        live.undo.clear()
        assertEquals(setOf("w77", "s1"), live.released.map { tagOf(it) }.toSet())
        assertNull(live.undo.next.value)
    }

    @Test
    fun aWidgetStillPinnedIsNeverFreed() {
        val live = Live(listOf(widget(1, 77, 0, 0), app(2, "a", 2, 0)))
        live.edit { TilePlacement.moveTo(it, 1, GridPos(0, 2), 4) }
        live.undo.clear()
        assertEquals(emptyList<TileSpec>(), live.released)
    }

    @Test
    fun undoFreesATileThatOnlyTheReplacedLayoutHad() {
        val live = Live(listOf(app(2, "a", 0, 0)))
        live.edit { TilePlacement.moveTo(it, 2, GridPos(2, 0), 4) }
        // A widget pinned after the edit, outside the undo steps.
        live.tiles = live.tiles!! + widget(5, 90, 0, 2)
        live.undo.undo()
        assertEquals(listOf(90), live.released.map { (it.content as TileContent.Widget).appWidgetId })
    }

    @Test
    fun foldersChildrenAreHolders() {
        val folder = TileSpec(9, TileSize.MEDIUM, TileContent.Folder("f", listOf(shortcut(10, "x"), shortcut(11, "y"))))
        val held = TileReferences.held(listOf(listOf(shortcut(1, "y", label = "renamed"))))
        assertEquals(listOf(10L), TileReferences.toRelease(listOf(folder), held).map { it.id })
    }

    @Test
    fun labelsPerEdit() {
        assertEquals("phone unpinned", StartEdit.UNPIN.label("Phone"))
        assertEquals("tile unpinned", StartEdit.UNPIN.label(" "))
        assertEquals("tile resized", StartEdit.RESIZE.label())
        assertEquals("tile moved", StartEdit.MOVE.label())
        assertEquals("folder created", StartEdit.FOLDER_CREATED.label())
        assertEquals("added to folder", StartEdit.FOLDER_ADDED.label())
        assertEquals("removed from folder", StartEdit.FOLDER_REMOVED_FROM.label())
        assertEquals("folder renamed", StartEdit.FOLDER_RENAMED.label())
        assertEquals("tile style changed", StartEdit.STYLE.label())
    }

    private fun tagOf(spec: TileSpec) = when (val c = spec.content) {
        is TileContent.Widget -> "w${c.appWidgetId}"
        is TileContent.Shortcut -> c.shortcutId
        else -> "?"
    }
}
