package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile

/** How long a layout switch's fades and size glides take, before the animation speed setting. */
internal const val SWITCH_MILLIS = 260

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

    @Synchronized
    fun keyOf(id: Long): Long {
        aliases[id]?.let { return it }
        // Another tile kept this id as its key: this one needs one of its own.
        if (aliases.containsValue(id)) return nextFresh--.also { aliases[id] = it }
        return id
    }

    /** [to] replaces [from] on start; see [TileMatching.match]. */
    @Synchronized
    fun switched(from: List<TileSpec>, to: List<TileSpec>) {
        val fromKeys = from.associate { it.id to keyOf(it.id) }
        val match = TileMatching.match(from, to)
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

/**
 * The extras of a layout switch on start, on top of the placement animation that moves the tiles
 * both layouts have: those also grow or shrink from their old size, tiles that leave fade and
 * shrink out where they were, and new ones fade in. Everything is read while drawing.
 */
@Stable
internal class SwitchMotion {
    /** A tile that left with the switch, drawn where it was until it has faded. */
    class Leaving(val key: Long, val tile: ResolvedTile, val placement: Placement)

    private var seenGeneration = -1
    private var shown: Map<Long, Pair<ResolvedTile, Placement>> = emptyMap()

    var leaving: List<Leaving> = emptyList()
        private set
    private var entering: Set<Long> = emptySet()
    private var resized: Map<Long, Placement> = emptyMap()

    /** 0 when a switch has just happened, 1 once its extras are done. */
    val progress = Animatable(1f)

    /** Recomposes start when the extras of a switch are over and the leaving tiles can go. */
    var ended by mutableIntStateOf(0)
        private set

    /**
     * Called while composing start with the tiles it shows by key: on a new [generation] works
     * out what left, what is new and what changed size. Returns whether a switch just happened.
     */
    fun update(generation: Int, now: Map<Long, Pair<ResolvedTile, Placement>>, animate: () -> Boolean): Boolean {
        val switched = seenGeneration >= 0 && generation != seenGeneration
        seenGeneration = generation
        val before = shown
        shown = now
        if (!switched) return false
        pending = true
        // Animations off (the system's animator scale): the new layout just appears.
        if (!animate()) return true
        leaving = before.filterKeys { it !in now }.map { (key, value) -> Leaving(key, value.first, value.second) }
        entering = now.keys - before.keys
        resized = now.mapNotNull { (key, value) ->
            val old = before[key]?.second ?: return@mapNotNull null
            if (old.width != value.second.width || old.height != value.second.height) key to old else null
        }.toMap()
        return true
    }

    fun isEntering(key: Long): Boolean = key in entering

    private var pending = false

    /** Whether a switch happened that the screen hasn't reacted to yet; true once per switch. */
    fun takeSwitch(): Boolean = pending.also { pending = false }

    suspend fun play(durationMillis: Int) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
        finish()
    }

    private fun finish() {
        leaving = emptyList()
        entering = emptySet()
        resized = emptyMap()
        ended++
    }

    /** The draw-only part for the tile with [key] now at [placement]: a size glide, or a fade and shrink when [isLeaving]. */
    fun modifier(key: Long, placement: Placement, isLeaving: Boolean = false): Modifier {
        val old = resized[key]
        if (!isLeaving && old == null) return Modifier
        return Modifier.graphicsLayer {
            val p = progress.value
            if (isLeaving) {
                alpha = 1f - p
                scaleX = lerp(1f, 0.8f, p)
                scaleY = lerp(1f, 0.8f, p)
            } else if (old != null && p < 1f) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = lerp(old.width.toFloat() / placement.width, 1f, p)
                scaleY = lerp(old.height.toFloat() / placement.height, 1f, p)
            }
        }
    }
}
