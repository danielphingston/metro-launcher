package io.github.danielphingston.metro.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.ui.start.TileKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The saved start layouts (see [LayoutSet], stored under [LayoutsJson.KEY] beside the tiles) and
 * switching between them. The set follows the store, so a restored backup shows up by itself.
 * Every operation runs on the main thread, one at a time.
 *
 * Widget ids and pinned shortcuts used by any saved layout stay held ([heldTiles]); a layout
 * that is deleted frees what nothing else uses any more, through [undo].
 */
class StartLayoutRepository(
    private val context: Context,
    scope: CoroutineScope,
    private val tiles: TileRepository,
    private val undo: () -> TileUndo,
) {
    private val key = stringPreferencesKey(LayoutsJson.KEY)
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    private val _layouts = MutableStateFlow<LayoutSet?>(null)

    /** The saved layouts; null while there are none (or before they are read). */
    val layouts: StateFlow<LayoutSet?> = _layouts.asStateFlow()

    /** Which composition each tile uses on start, so tiles in both layouts of a switch glide; see [TileKeys]. */
    val keys = TileKeys()

    /** A switch that happened: to [name], from layout [previous] (for "back"). */
    class Switched(val name: String, val previous: Long)

    private val _switches = MutableSharedFlow<Switched>(extraBufferCapacity = 1)
    val switches: SharedFlow<Switched> = _switches.asSharedFlow()

    @Volatile
    private var held: Pair<LayoutSet, List<List<TileSpec>>>? = null

    init {
        scope.launch {
            context.metroDataStore.data.map(::stored).distinctUntilChanged().collect {
                _layouts.value = LayoutsJson.decode(it)
                loaded.complete(Unit)
            }
        }
    }

    /** Read leniently: a backup could put another kind of value under the key, which must not crash the launcher. */
    private fun stored(prefs: Preferences): String? = prefs.asMap().entries.firstOrNull { it.key.name == LayoutsJson.KEY }?.value as? String

    suspend fun awaitLoaded() = loaded.await()

    /** The tiles of the layouts that are not on screen, which keep their widget ids and shortcuts. */
    fun heldTiles(): List<List<TileSpec>> {
        val set = _layouts.value ?: return emptyList()
        held?.takeIf { it.first === set }?.let { return it.second }
        return set.inactiveTiles().also { held = set to it }
    }

    /** Whether a layout not on screen has entries this version can't read; then no widget id is reclaimed. */
    fun hasUndecoded(): Boolean = _layouts.value?.hasUndecoded() == true

    private suspend fun write(set: LayoutSet): Boolean = try {
        context.metroDataStore.edit { it[key] = LayoutsJson.encode(set) }
        _layouts.value = set
        true
    } catch (_: IOException) {
        false
    }

    /** Runs [change] on the set and the live tiles and writes the result; false when it didn't apply or couldn't be written. */
    private suspend fun update(change: (LayoutSet?, String) -> LayoutSet?): Boolean = mutex.withLock {
        val live = tiles.encoded() ?: return false
        val next = change(_layouts.value, live) ?: return false
        write(next)
    }

    /** Saves the current start as a new layout called [name]; returns why not, or null when it was saved. */
    suspend fun saveCurrentAs(name: String): String? {
        LayoutNames.problem(LayoutNames.clean(name), _layouts.value)?.let { return it }
        return if (update { set, live -> LayoutOps.saveAs(set, live, name) }) null else "couldn't save the layout"
    }

    /** Renames layout [id]; returns why not, or null when it was renamed. */
    suspend fun rename(id: Long, name: String): String? {
        LayoutNames.problem(LayoutNames.clean(name), _layouts.value, exceptId = id)?.let { return it }
        return if (update { set, live -> set?.let { LayoutOps.rename(it, id, name, live) } }) null else "couldn't rename the layout"
    }

    suspend fun duplicate(id: Long): Boolean = update { set, live -> set?.let { LayoutOps.duplicate(it, id, live) } }

    /** Deletes layout [id] (never the active one) and frees the widget ids and shortcuts only it used. */
    suspend fun delete(id: Long): Boolean {
        var gone: SavedLayout? = null
        val done = update { set, live ->
            set?.let { LayoutOps.delete(it, id, live) }?.let { (next, removed) ->
                gone = removed
                next
            }
        }
        gone?.takeIf { done }?.let { layout -> undo().releaseUnreferenced(TileJson.read(layout.tiles)?.tiles.orEmpty()) }
        return done
    }

    /**
     * Shows layout [id] on start: the live tiles go back into the active entry and [id]'s tiles
     * become the live ones, in one store edit. The undo steps belonged to the other layout and
     * are dropped. False for the active layout, a missing one, or a failed write.
     */
    suspend fun switchTo(id: Long): Boolean = mutex.withLock {
        val set = _layouts.value ?: return false
        val live = tiles.encoded() ?: return false
        val next = LayoutOps.switch(set, live, id) ?: return false
        val from = tiles.tiles.value.orEmpty()
        val encoded = LayoutsJson.encode(next)
        val swapped = tiles.swap(next.byId(id)!!.tiles, beforePublish = { keys.switched(from, it) }) { prefs -> prefs[key] = encoded }
        if (!swapped) return false
        _layouts.value = next
        undo().clear()
        _switches.tryEmit(Switched(next.activeLayout.name, set.active))
        true
    }

    /** Switches to the layout after the active one; false when there is no other. */
    suspend fun switchToNext(): Boolean {
        val next = _layouts.value?.next() ?: return false
        return switchTo(next.id)
    }
}
