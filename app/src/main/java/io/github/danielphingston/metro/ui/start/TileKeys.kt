package io.github.danielphingston.metro.ui.start

import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSpec

/** Which tiles of two layouts are "the same tile", for the switch animation; pure. */
internal object TileMatching {
    /**
     * What makes a tile the same in another layout: its app, shortcut, widget id, or the kind of
     * a one-per-layout tile. Folders have none (they match by id).
     */
    fun identity(content: TileContent): Any? = when (content) {
        is TileContent.App -> content.app
        is TileContent.Shortcut -> Triple(content.packageName, content.shortcutId, content.userSerial)
        is TileContent.Widget -> "widget" to content.appWidgetId
        is TileContent.Folder -> null
        else -> content // Weather, photos, people, the assistant, a quick setting, an action or a switch tile.
    }

    /**
     * Each tile of [to] that is also in [from], by its [identity] first, then by the same tile id
     * and kind: to-id to from-id, each tile matched at most once.
     */
    fun match(from: List<TileSpec>, to: List<TileSpec>): Map<Long, Long> {
        val result = LinkedHashMap<Long, Long>()
        val taken = HashSet<Long>()
        val byIdentity = HashMap<Any, ArrayDeque<TileSpec>>()
        from.forEach { spec -> identity(spec.content)?.let { byIdentity.getOrPut(it) { ArrayDeque() }.addLast(spec) } }
        to.forEach { spec ->
            val match = identity(spec.content)?.let { byIdentity[it]?.removeFirstOrNull() } ?: return@forEach
            result[spec.id] = match.id
            taken += match.id
        }
        val fromById = from.associateBy { it.id }
        to.forEach { spec ->
            if (spec.id in result) return@forEach
            val same = fromById[spec.id]?.takeIf { it.id !in taken && it.content::class == spec.content::class } ?: return@forEach
            result[spec.id] = same.id
            taken += same.id
        }
        return result
    }
}

/**
 * The composition key of each tile on start. Normally a tile's own id; after a layout switch a
 * tile that was already on screen keeps its old key (so the existing placement animation moves it,
 * and a widget keeps its view), and any other tile gets a key no tile on screen had. Main thread.
 */
class TileKeys {
    private val aliases = HashMap<Long, Long>()
    private var nextFresh = -1L

    /** Bumped by every switch; the start screen animates when it sees a new one. */
    var generation = 0
        private set

    /**
     * Whether the latest switch may change the grid (columns or paddings). Its settings land just
     * after its tiles, so the start screen waits briefly for them before tiles move to the new grid.
     */
    var gridChanges = false
        private set

    @Synchronized
    fun keyOf(id: Long): Long {
        aliases[id]?.let { return it }
        // Another tile kept this id as its key: this one needs one of its own.
        if (aliases.containsValue(id)) return nextFresh--.also { aliases[id] = it }
        return id
    }

    /** [to] replaces [from] on start; see [TileMatching.match] and [gridChanges]. */
    @Synchronized
    fun switched(from: List<TileSpec>, to: List<TileSpec>, gridChanges: Boolean = false) {
        val fromKeys = from.associate { it.id to keyOf(it.id) }
        val match = TileMatching.match(from, to)
        this.gridChanges = gridChanges
        val next = HashMap<Long, Long>()
        val used = HashSet<Long>()
        match.forEach { (toId, fromId) -> fromKeys[fromId]?.let { next[toId] = it; used += it } }
        val onScreen = fromKeys.values.toHashSet()
        to.forEach { spec ->
            if (spec.id !in next) next[spec.id] = if (spec.id in used || spec.id in onScreen) nextFresh-- else spec.id
        }
        aliases.clear()
        next.forEach { (id, key) -> if (id != key) aliases[id] = key }
        generation++
    }
}
