package io.github.danielphingston.metro.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.live.PhotoSource
import io.github.danielphingston.metro.ui.start.TileKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
    /** Layouts are part of Metro Plus: while it is locked nothing is saved, copied or switched (what is stored stays). */
    private val unlocked: () -> Boolean = { true },
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
            // Decoding every saved layout is real work (all their tile JSON): off the main thread, which a switch would stall.
            context.metroDataStore.data.map(::stored).distinctUntilChanged().map(LayoutsJson::decode).flowOn(Dispatchers.Default).collect {
                _layouts.value = it
                loaded.complete(Unit)
            }
        }
    }

    /** Read leniently: a backup could put another kind of value under the key, which must not crash the launcher. */
    private fun stored(prefs: Preferences): String? = prefs.asMap().entries.firstOrNull { it.key.name == LayoutsJson.KEY }?.value as? String

    suspend fun awaitLoaded() = loaded.await()

    /** Whether layouts can be used now; see the constructor. */
    val isUnlocked: Boolean get() = unlocked()

    companion object {
        const val LOCKED = "layouts are a Metro Plus feature"
    }

    /** The tiles of the layouts that are not on screen, which keep their widget ids and shortcuts. */
    fun heldTiles(): List<List<TileSpec>> {
        val set = _layouts.value ?: return emptyList()
        held?.takeIf { it.first === set }?.let { return it.second }
        return set.inactiveTiles().also { held = set to it }
    }

    /** Whether a layout not on screen has entries this version can't read; then no widget id is reclaimed. */
    fun hasUndecoded(): Boolean = _layouts.value?.hasUndecoded() == true

    /**
     * Runs [change] on the set, the live tiles and the live per-layout settings, and writes the
     * result; false when it didn't apply or couldn't be written. The settings are read inside the
     * same store edit, so a setting changed just before can't be missed.
     */
    private suspend fun update(change: (LayoutSet?, String, Map<String, Any>) -> LayoutSet?): Boolean = mutex.withLock {
        val live = tiles.encoded() ?: return false
        val current = _layouts.value
        var written: LayoutSet? = null
        try {
            context.metroDataStore.edit { prefs ->
                val next = change(current, live, LayoutStartSettings.read(prefs)) ?: return@edit
                prefs[key] = LayoutsJson.encode(next)
                written = next
            }
        } catch (_: IOException) {
            return false
        }
        written?.let { _layouts.value = it }
        written != null
    }

    /** Saves the current start, its settings included, as a new layout called [name]; returns why not, or null when it was saved. */
    suspend fun saveCurrentAs(name: String): String? {
        if (!unlocked()) return LOCKED
        LayoutNames.problem(LayoutNames.clean(name), _layouts.value)?.let { return it }
        return if (update { set, live, settings -> LayoutOps.saveAs(set, live, name, settings) }) null else "couldn't save the layout"
    }

    /** Adds a layout with no tiles and the current start settings, without switching to it; returns why not, or null when it was added. */
    suspend fun createEmpty(name: String): String? {
        if (!unlocked()) return LOCKED
        LayoutNames.problem(LayoutNames.clean(name), _layouts.value)?.let { return it }
        return if (update { set, live, settings -> LayoutOps.newEmpty(set, live, name, settings) }) null else "couldn't add the layout"
    }

    /** Renames layout [id]; returns why not, or null when it was renamed. */
    suspend fun rename(id: Long, name: String): String? {
        LayoutNames.problem(LayoutNames.clean(name), _layouts.value, exceptId = id)?.let { return it }
        return if (update { set, live, settings -> set?.let { LayoutOps.rename(it, id, name, live, settings) } }) null else "couldn't rename the layout"
    }

    suspend fun duplicate(id: Long): Boolean =
        unlocked() && update { set, live, settings -> set?.let { LayoutOps.duplicate(it, id, live, settings) } }

    /**
     * Deletes layout [id] (never the active one) and frees the widget ids, shortcuts and
     * background photo only it used.
     */
    suspend fun delete(id: Long): Boolean {
        var gone: SavedLayout? = null
        val done = update { set, live, settings ->
            set?.let { LayoutOps.delete(it, id, live, settings) }?.let { (next, removed) ->
                gone = removed
                next
            }
        }
        gone?.takeIf { done }?.let { layout ->
            undo().releaseUnreferenced(TileJson.read(layout.tiles)?.tiles.orEmpty())
            (layout.settings?.get(LayoutStartSettings.PHOTO) as? String)?.let { releasePhotosIfUnused(listOf(it)) }
        }
        return done
    }

    /**
     * Lets go of the persisted read grant of each of [uris] that no layout's background, the live
     * background, the photos tile and no tile photo (on start or in a saved layout) use any more
     * (see [LayoutStartSettings.photosInUse], [TileOptions.photosIn]).
     */
    suspend fun releasePhotosIfUnused(uris: Collection<String>) {
        val candidates = uris.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return
        val livePhoto = context.metroDataStore.data.first().let { LayoutStartSettings.read(it)[LayoutStartSettings.PHOTO] as? String }
        val inUse = LayoutStartSettings.photosInUse(_layouts.value, livePhoto) + PhotoSource.current(context) +
            TileOptions.photosIn(listOfNotNull(tiles.tiles.value) + heldTiles())
        candidates.filter { it !in inUse }.forEach { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * Shows layout [id] on start: the live tiles and per-layout settings go back into the active
     * entry, and [id]'s tiles and settings become the live ones, all in one store edit. An entry
     * without settings leaves the live settings as they are. The undo steps belonged to the other
     * layout and are dropped. False for the active layout, a missing one, or a failed write.
     */
    suspend fun switchTo(id: Long): Boolean = mutex.withLock {
        if (!unlocked()) return false
        val set = _layouts.value ?: return false
        val live = tiles.encoded() ?: return false
        val target = set.byId(id)?.takeIf { id != set.active } ?: return false
        val from = tiles.tiles.value.orEmpty()
        // Tiles glide to their new places only when the grid's cells stay the same size.
        val before = LayoutStartSettings.read(context.metroDataStore.data.first())
        val glide = !LayoutStartSettings.changesGrid(before, target.settings)
        var next: LayoutSet? = null
        val swapped = tiles.swap(target.tiles, beforePublish = { keys.switched(from, it, glide) }) { prefs ->
            next = LayoutOps.switchStore(prefs, set, live, id)
        }
        val done = next
        if (!swapped || done == null) return false
        _layouts.value = done
        undo().clear()
        _switches.tryEmit(Switched(done.activeLayout.name, set.active))
        true
    }

    /** Switches to the layout after the active one; false when there is no other. */
    suspend fun switchToNext(): Boolean {
        val next = _layouts.value?.next() ?: return false
        return switchTo(next.id)
    }
}
