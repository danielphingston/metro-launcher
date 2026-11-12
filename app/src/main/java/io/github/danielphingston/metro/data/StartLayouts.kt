package io.github.danielphingston.metro.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved start layout. [tiles] is a tile array exactly as the "tiles" key stores it (see
 * [TileJson]), kept as text so entries this version can't decode survive untouched. [settings]
 * are its start settings (see [LayoutStartSettings]): the stored value of each per-layout key that
 * is set; null for an entry saved before layouts kept settings, which leaves the live ones alone.
 */
data class SavedLayout(val id: Long, val name: String, val tiles: String, val settings: Map<String, Any>? = null) {
    // Reading the tile JSON is real work: once per layout (decoding warms it, off the main thread).
    private val noTiles = lazy(LazyThreadSafetyMode.PUBLICATION) {
        TileJson.read(tiles)?.let { read ->
            read.tiles.isEmpty() && read.unread.top.isEmpty() && read.unread.children.values.all { it.isEmpty() }
        } == true
    }

    /** Whether this layout holds no tiles at all (entries this version can't read count as tiles). */
    val hasNoTiles: Boolean get() = noTiles.value

    /** Whether [hasNoTiles] is already known, so reading it does no work. */
    internal val knowsWhetherEmpty: Boolean get() = noTiles.isInitialized()
}

/**
 * The saved start layouts, of which [active] is the one on screen. The live "tiles" key holds the
 * active layout's tiles; its entry here is only brought up to date when the layouts are written
 * (a switch, a save, a rename...), so it may lag behind the screen in between.
 */
data class LayoutSet(val active: Long, val layouts: List<SavedLayout>) {
    fun byId(id: Long): SavedLayout? = layouts.firstOrNull { it.id == id }

    val activeLayout: SavedLayout get() = byId(active) ?: layouts.first()

    /** This set with layout [id]'s tiles replaced by [tiles]. */
    fun withTiles(id: Long, tiles: String): LayoutSet = copy(layouts = layouts.map { if (it.id == id) it.copy(tiles = tiles) else it })

    /**
     * This set with the screen as it is written into the active entry: the live [tiles] and, unless
     * null, the live per-layout [settings].
     */
    fun withLive(tiles: String, settings: Map<String, Any>?): LayoutSet = copy(
        layouts = layouts.map { if (it.id == active) it.copy(tiles = tiles, settings = settings ?: it.settings) else it },
    )

    /** The layout after the active one, wrapping around; null when there is no other. */
    fun next(): SavedLayout? {
        if (layouts.size < 2) return null
        val index = layouts.indexOfFirst { it.id == active }
        return layouts[(index + 1).mod(layouts.size)]
    }

    /** The tiles of every layout but the active one (its tiles are on screen): what they keep holding. */
    fun inactiveTiles(): List<List<TileSpec>> =
        layouts.filter { it.id != active }.map { TileJson.read(it.tiles)?.tiles.orEmpty() }

    /** Whether some inactive layout holds entries this version can't read (a widget tile of a newer build, say). */
    fun hasUndecoded(): Boolean = layouts.filter { it.id != active }.any { layout ->
        val read = TileJson.read(layout.tiles) ?: return@any true
        read.unread.top.isNotEmpty() || read.unread.children.values.any { it.isNotEmpty() }
    }
}

/**
 * The "layouts" key:
 * {"active": <id>, "layouts": [{"id": <long>, "name": "...", "tiles": [...], "settings": {"<key>": <value>}}]}.
 * "settings" is optional (see [LayoutStartSettings]). An install that never saved a layout has no
 * such key. Part of every backup, so its shape is a promise: only ever add fields, and read what
 * isn't there as before.
 */
internal object LayoutsJson {
    const val KEY = "layouts"

    fun encode(set: LayoutSet): String = JSONObject()
        .put("active", set.active)
        .put(
            "layouts",
            JSONArray().apply {
                set.layouts.forEach { layout ->
                    val entry = JSONObject().put("id", layout.id).put("name", layout.name).put("tiles", JSONArray(layout.tiles))
                    layout.settings?.let { entry.put("settings", LayoutStartSettings.encode(it)) }
                    put(entry)
                }
            },
        )
        .toString()

    /**
     * The layouts in [json], or null when there are none: no value, or one that isn't a layout
     * set. Entries without an id or a tile array, and repeated ids, are dropped; an active id
     * that names no layout falls back to the first.
     */
    fun decode(json: String?): LayoutSet? {
        val root = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val array = root.optJSONArray("layouts") ?: return null
        val seen = HashSet<Long>()
        val layouts = (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = runCatching { obj.getLong("id") }.getOrNull() ?: return@mapNotNull null
            val tiles = obj.optJSONArray("tiles") ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            SavedLayout(
                id,
                LayoutNames.clean(obj.optString("name")).ifEmpty { "layout $id" },
                tiles.toString(),
                LayoutStartSettings.decode(obj.optJSONObject("settings")),
            )
        }
        if (layouts.isEmpty()) return null
        // Known here, off the main thread, so the layouts page doesn't read every layout's tiles as it draws.
        layouts.forEach { it.hasNoTiles }
        val active = root.optLong("active", layouts.first().id).takeIf { id -> layouts.any { it.id == id } } ?: layouts.first().id
        return LayoutSet(active, layouts)
    }
}

/** Layout names: trimmed, single-spaced, at most [MAX] characters, unique regardless of case. */
object LayoutNames {
    const val MAX = 24

    /** The name the first layout gets when the current start is saved for the first time. */
    const val FIRST = "start"

    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX).trim()

    /** Why [name] (already [clean]) can't name a layout among [set] other than [exceptId], or null if it can. */
    fun problem(name: String, set: LayoutSet?, exceptId: Long? = null): String? = when {
        name.isEmpty() -> "type a name"
        taken(name, set, exceptId) -> "there's already a layout called $name"
        else -> null
    }

    private fun taken(name: String, set: LayoutSet?, exceptId: Long?): Boolean {
        // Before the first save, the current start is about to become "start".
        val names = set?.layouts?.filter { it.id != exceptId }?.map { it.name } ?: listOf(FIRST)
        return names.any { it.equals(name, ignoreCase = true) }
    }

    /** [base], or "[base] 2", "[base] 3"... whichever is free in [set]. */
    fun unique(base: String, set: LayoutSet?): String {
        val clean = clean(base).ifEmpty { "layout" }
        if (!taken(clean, set, null)) return clean
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = clean.take(MAX - suffix.length).trim() + suffix
            if (!taken(candidate, set, null)) return candidate
            n++
        }
    }
}

/**
 * The layout operations as pure functions over a [LayoutSet], the live tiles ([live], as the tiles
 * key stores them) and the live per-layout settings ([settings], see [LayoutStartSettings]; null
 * keeps the stored ones). Each writes the live state back into the active entry, so whatever is
 * written next has the screen as it is. Null means the operation doesn't apply.
 */
internal object LayoutOps {
    private fun nextId(set: LayoutSet) = (set.layouts.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * Keeps a copy of the current start, its settings included, as a new layout called [name];
     * the current one stays active. The first time, the current start itself becomes the layout
     * [LayoutNames.FIRST].
     */
    fun saveAs(set: LayoutSet?, live: String, name: String, settings: Map<String, Any>? = null): LayoutSet? {
        val clean = LayoutNames.clean(name)
        if (LayoutNames.problem(clean, set) != null) return null
        val base = (set ?: LayoutSet(1, listOf(SavedLayout(1, LayoutNames.FIRST, live)))).withLive(live, settings)
        return base.copy(layouts = base.layouts + SavedLayout(nextId(base), clean, live, base.activeLayout.settings))
    }

    /**
     * Adds a layout called [name] with no tiles and the current start settings; the current layout
     * stays active (the user switches when ready). The first time, the current start itself becomes
     * the layout [LayoutNames.FIRST], as with [saveAs]. An empty layout references no widget or shortcut.
     */
    fun newEmpty(set: LayoutSet?, live: String, name: String, settings: Map<String, Any>? = null): LayoutSet? {
        val clean = LayoutNames.clean(name)
        if (LayoutNames.problem(clean, set) != null) return null
        val base = (set ?: LayoutSet(1, listOf(SavedLayout(1, LayoutNames.FIRST, live)))).withLive(live, settings)
        return base.copy(layouts = base.layouts + SavedLayout(nextId(base), clean, "[]", base.activeLayout.settings))
    }

    fun rename(set: LayoutSet, id: Long, name: String, live: String, settings: Map<String, Any>? = null): LayoutSet? {
        val clean = LayoutNames.clean(name)
        if (set.byId(id) == null || LayoutNames.problem(clean, set, exceptId = id) != null) return null
        val written = set.withLive(live, settings)
        return written.copy(layouts = written.layouts.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /**
     * A copy of layout [id], tiles and settings, right after it, named "<name> copy"; the active
     * layout is copied as it is on screen. Widget tiles share their ids with the original.
     */
    fun duplicate(set: LayoutSet, id: Long, live: String, settings: Map<String, Any>? = null): LayoutSet? {
        val written = set.withLive(live, settings)
        val source = written.byId(id) ?: return null
        val copy = SavedLayout(nextId(set), LayoutNames.unique("${source.name} copy", set), source.tiles, source.settings)
        val index = written.layouts.indexOf(source)
        return written.copy(layouts = written.layouts.toMutableList().apply { add(index + 1, copy) })
    }

    /** Removes layout [id] and returns it; never the active one. */
    fun delete(set: LayoutSet, id: Long, live: String, settings: Map<String, Any>? = null): Pair<LayoutSet, SavedLayout>? {
        val gone = set.byId(id)?.takeIf { id != set.active } ?: return null
        val written = set.withLive(live, settings)
        return written.copy(layouts = written.layouts.filter { it.id != id }) to gone
    }

    /**
     * Makes layout [target] active, keeping the live tiles and settings in the one that was. The
     * caller then loads the target's tiles, and its settings when it has any ([settingsToLoad]).
     * Null for the active layout or a missing one.
     */
    fun switch(set: LayoutSet, live: String, target: Long, settings: Map<String, Any>? = null): LayoutSet? {
        if (target == set.active || set.byId(target) == null) return null
        return set.withLive(live, settings).copy(active = target)
    }

    /**
     * What the live per-layout keys hold after switching to [switched]'s active layout: its
     * settings, every per-layout key they lack removed (see [LayoutStartSettings.apply]). Null, for
     * an entry saved without settings, leaves the live settings as they are.
     */
    fun settingsToLoad(switched: LayoutSet): Map<String, Any>? = switched.activeLayout.settings

    /**
     * The store's side of a switch to [target], inside the one edit that also writes the target's
     * tiles: the live settings in [prefs] go into the active entry, the layouts are written, and
     * the target's settings become the live ones. Returns the new set, or null when it doesn't apply.
     */
    fun switchStore(prefs: MutablePreferences, set: LayoutSet, live: String, target: Long): LayoutSet? {
        val switched = switch(set, live, target, LayoutStartSettings.read(prefs)) ?: return null
        prefs[stringPreferencesKey(LayoutsJson.KEY)] = LayoutsJson.encode(switched)
        settingsToLoad(switched)?.let { LayoutStartSettings.apply(prefs, it) }
        return switched
    }
}
