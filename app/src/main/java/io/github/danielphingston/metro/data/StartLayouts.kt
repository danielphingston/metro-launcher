package io.github.danielphingston.metro.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved start layout. [tiles] is a tile array exactly as the "tiles" key stores it (see
 * [TileJson]), kept as text so entries this version can't decode survive untouched.
 */
data class SavedLayout(val id: Long, val name: String, val tiles: String)

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
 * The "layouts" key: {"active": <id>, "layouts": [{"id": <long>, "name": "...", "tiles": [...]}]}.
 * An install that never saved a layout has no such key. Part of every backup, so its shape is a
 * promise: only ever add fields, and read what isn't there as before.
 */
internal object LayoutsJson {
    const val KEY = "layouts"

    fun encode(set: LayoutSet): String = JSONObject()
        .put("active", set.active)
        .put(
            "layouts",
            JSONArray().apply {
                set.layouts.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("tiles", JSONArray(it.tiles))) }
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
            SavedLayout(id, LayoutNames.clean(obj.optString("name")).ifEmpty { "layout $id" }, tiles.toString())
        }
        if (layouts.isEmpty()) return null
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
 * The layout operations as pure functions over a [LayoutSet] and the live tiles ([live], as the
 * tiles key stores them). Each writes the live tiles back into the active entry, so whatever is
 * written next has the screen as it is. Null means the operation doesn't apply.
 */
internal object LayoutOps {
    private fun nextId(set: LayoutSet) = (set.layouts.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * Keeps a copy of the current start as a new layout called [name]; the current one stays
     * active. The first time, the current start itself becomes the layout [LayoutNames.FIRST].
     */
    fun saveAs(set: LayoutSet?, live: String, name: String): LayoutSet? {
        val clean = LayoutNames.clean(name)
        if (LayoutNames.problem(clean, set) != null) return null
        val base = (set ?: LayoutSet(1, listOf(SavedLayout(1, LayoutNames.FIRST, live)))).withTiles(set?.active ?: 1, live)
        return base.copy(layouts = base.layouts + SavedLayout(nextId(base), clean, live))
    }

    fun rename(set: LayoutSet, id: Long, name: String, live: String): LayoutSet? {
        val clean = LayoutNames.clean(name)
        if (set.byId(id) == null || LayoutNames.problem(clean, set, exceptId = id) != null) return null
        return set.withTiles(set.active, live).copy(layouts = set.layouts.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** A copy of layout [id] right after it, named "<name> copy". Widget tiles share their ids with the original. */
    fun duplicate(set: LayoutSet, id: Long, live: String): LayoutSet? {
        val written = set.withTiles(set.active, live)
        val source = written.byId(id) ?: return null
        val copy = SavedLayout(nextId(set), LayoutNames.unique("${source.name} copy", set), source.tiles)
        val index = written.layouts.indexOf(source)
        return written.copy(layouts = written.layouts.toMutableList().apply { add(index + 1, copy) })
    }

    /** Removes layout [id] and returns it; never the active one. */
    fun delete(set: LayoutSet, id: Long, live: String): Pair<LayoutSet, SavedLayout>? {
        val gone = set.byId(id)?.takeIf { id != set.active } ?: return null
        return set.withTiles(set.active, live).copy(layouts = set.layouts.filter { it.id != id }) to gone
    }

    /** Makes layout [target] active, keeping the live tiles in the one that was. Null for the active layout or a missing one. */
    fun switch(set: LayoutSet, live: String, target: Long): LayoutSet? {
        if (target == set.active || set.byId(target) == null) return null
        return set.withTiles(set.active, live).copy(active = target)
    }
}
