package io.github.danielphingston.metro.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A last-in first-out list that keeps at most [capacity] items, forgetting the oldest. */
internal class UndoStack<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()

    val size: Int get() = items.size

    fun peek(): T? = items.lastOrNull()

    /** Adds [item] on top and returns the oldest items that no longer fit. */
    fun push(item: T): List<T> {
        items.addLast(item)
        val dropped = ArrayList<T>()
        while (items.size > capacity) dropped += items.removeFirst()
        return dropped
    }

    fun pop(): T? = items.removeLastOrNull()

    /** Empties the stack and returns what it held, oldest first. */
    fun clear(): List<T> = items.toList().also { items.clear() }

    fun toList(): List<T> = items.toList()
}

/** The start screen edits that can be taken back, and what the bar says after each. */
enum class StartEdit {
    UNPIN, RESIZE, MOVE, FOLDER_CREATED, FOLDER_ADDED, FOLDER_REMOVED_FROM, FOLDER_RENAMED, STYLE, ICON;

    /** The bar's text; [name] is the tile's label (for unpin). Lowercase, like the rest of Metro. */
    fun label(name: String? = null): String = when (this) {
        UNPIN -> "${name?.trim()?.takeIf { it.isNotEmpty() } ?: "tile"} unpinned"
        RESIZE -> "tile resized"
        MOVE -> "tile moved"
        FOLDER_CREATED -> "folder created"
        FOLDER_ADDED -> "added to folder"
        FOLDER_REMOVED_FROM -> "removed from folder"
        FOLDER_RENAMED -> "folder renamed"
        STYLE -> "tile style changed"
        ICON -> "tile icon changed"
    }.lowercase()
}

/**
 * What the system holds on behalf of tiles (app widget ids, pinned shortcuts) and which of it is
 * still used. A tile's resources are freed only once no layout and no undo step refers to them.
 */
internal object TileReferences {
    /** Shortcuts are the same pin whatever their label says. */
    data class ShortcutRef(val packageName: String, val shortcutId: String, val userSerial: Long)

    data class Held(val widgetIds: Set<Int>, val shortcuts: Set<ShortcutRef>)

    private fun TileContent.Shortcut.ref() = ShortcutRef(packageName, shortcutId, userSerial)

    /** [tiles] and the children of their folders that hold something: widgets and shortcuts. */
    fun holders(tiles: List<TileSpec>): List<TileSpec> =
        tiles.flatMap { spec -> listOf(spec) + ((spec.content as? TileContent.Folder)?.children.orEmpty()) }
            .filter { it.content is TileContent.Widget || it.content is TileContent.Shortcut }

    fun held(layouts: List<List<TileSpec>>): Held {
        val widgets = HashSet<Int>()
        val shortcuts = HashSet<ShortcutRef>()
        layouts.forEach { tiles ->
            holders(tiles).forEach {
                when (val c = it.content) {
                    is TileContent.Widget -> widgets += c.appWidgetId
                    is TileContent.Shortcut -> shortcuts += c.ref()
                    else -> Unit
                }
            }
        }
        return Held(widgets, shortcuts)
    }

    /** The holders among [candidates] nothing in [held] uses any more, each resource once. */
    fun toRelease(candidates: List<TileSpec>, held: Held): List<TileSpec> {
        val widgets = HashSet<Int>()
        val shortcuts = HashSet<ShortcutRef>()
        return holders(candidates).filter {
            when (val c = it.content) {
                is TileContent.Widget -> c.appWidgetId !in held.widgetIds && widgets.add(c.appWidgetId)
                is TileContent.Shortcut -> c.ref() !in held.shortcuts && shortcuts.add(c.ref())
                else -> false
            }
        }
    }
}

/**
 * Undo for start screen edits: whole-layout snapshots taken before each edit, at most [capacity],
 * in memory only (a process death forgets them). A widget id or pinned shortcut that an undo step
 * could bring back is not freed while that step is kept: it is freed when the step falls off the
 * stack or the stack is cleared, if nothing else refers to it by then. A process death in between
 * leaves at most widget ids behind, which the start of the next process reclaims.
 *
 * [current] reads the live layout and [restore] puts one back through the normal save path;
 * [alsoHeld] lists other layouts whose resources must stay (saved start layouts); [release] frees
 * the resources of the tiles given. Call from the main thread.
 */
class TileUndo(
    private val current: () -> List<TileSpec>?,
    private val restore: (List<TileSpec>) -> Unit,
    private val alsoHeld: () -> List<List<TileSpec>> = { emptyList() },
    private val release: (List<TileSpec>) -> Unit,
    capacity: Int = CAPACITY,
) {
    private class Step(val tiles: List<TileSpec>, val label: String)

    private val stack = UndoStack<Step>(capacity)

    private val _next = MutableStateFlow<String?>(null)

    /** What the next undo takes back (its bar text), or null when there is nothing to undo. */
    val next: StateFlow<String?> = _next.asStateFlow()

    private val _recorded = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The label of each edit as it is recorded, for the bar that offers to take it back. */
    val recorded: SharedFlow<String> = _recorded.asSharedFlow()

    /** The layouts the undo steps hold, newest last; their resources count as used. */
    fun snapshots(): List<List<TileSpec>> = synchronized(stack) { stack.toList().map { it.tiles } }

    /**
     * Runs [edit] and keeps the layout from before it as an undo step labelled [label], if the
     * edit changed anything. Returns whether a step was kept.
     */
    fun record(label: String, edit: () -> Unit): Boolean {
        val before = current()
        edit()
        val after = current()
        if (before == null || after == null || after == before) return false
        val dropped = synchronized(stack) { stack.push(Step(before, label)) }
        publish()
        _recorded.tryEmit(label)
        if (dropped.isNotEmpty()) releaseUnreferenced(dropped.flatMap { it.tiles })
        return true
    }

    /** Puts back the layout from before the latest edit; returns that edit's label, or null if there was none. */
    fun undo(): String? {
        val step = synchronized(stack) { stack.pop() } ?: return null
        val replaced = current().orEmpty()
        restore(step.tiles)
        publish()
        // Tiles that only the replaced layout had (one added after the edit, say) are gone for good.
        releaseUnreferenced(replaced)
        return step.label
    }

    /** Forgets every step, e.g. when another layout replaced the start screen; frees what only they held. */
    fun clear() {
        val dropped = synchronized(stack) { stack.clear() }
        if (dropped.isEmpty()) return
        publish()
        releaseUnreferenced(dropped.flatMap { it.tiles })
    }

    /** Frees the resources of [tiles] that neither the live layout, an undo step nor [alsoHeld] uses. */
    fun releaseUnreferenced(tiles: List<TileSpec>) {
        if (TileReferences.holders(tiles).isEmpty()) return
        val held = TileReferences.held(listOfNotNull(current()) + snapshots() + alsoHeld())
        val free = TileReferences.toRelease(tiles, held)
        if (free.isNotEmpty()) release(free)
    }

    private fun publish() {
        _next.value = synchronized(stack) { stack.peek()?.label }
    }

    companion object {
        const val CAPACITY = 20
    }
}
