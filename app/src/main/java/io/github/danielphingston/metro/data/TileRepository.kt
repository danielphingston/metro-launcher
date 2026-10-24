package io.github.danielphingston.metro.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class TileSize(val width: Int, val height: Int) {
    SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2), LARGE(4, 4);

    /** Windows Phone's resize button order. */
    fun next(): TileSize = when (this) {
        MEDIUM -> SMALL
        SMALL -> WIDE
        WIDE -> LARGE
        LARGE -> MEDIUM
    }
}

data class TileSpec(val id: Long, val app: AppKey, val size: TileSize)

/**
 * The ordered list of pinned tiles. Order is what the user arranges; the grid position is
 * derived from it by [io.github.danielphingston.metro.ui.start.TileGrid.pack].
 */
class TileRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("tiles")

    /** Null until loaded. [isFirstRun] tells whether a layout has ever been saved. */
    private val _tiles = MutableStateFlow<List<TileSpec>?>(null)
    val tiles: StateFlow<List<TileSpec>?> = _tiles.asStateFlow()

    var isFirstRun = false
        private set

    init {
        scope.launch {
            val stored = context.metroDataStore.data.first()[key]
            isFirstRun = stored == null
            _tiles.value = stored?.let(::decode).orEmpty()
            persistChanges()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persistChanges() {
        _tiles.filterNotNull().drop(1).debounce(400).collect { list ->
            context.metroDataStore.edit { it[key] = encode(list) }
        }
    }

    fun replaceAll(tiles: List<TileSpec>) {
        isFirstRun = false
        _tiles.value = tiles
    }

    fun pin(app: AppKey, size: TileSize = TileSize.MEDIUM) = _tiles.update { current ->
        val list = current.orEmpty()
        if (list.any { it.app == app }) list
        else list + TileSpec(id = (list.maxOfOrNull { it.id } ?: 0) + 1, app = app, size = size)
    }

    fun unpin(id: Long) = _tiles.update { it?.filterNot { tile -> tile.id == id } }

    fun resize(id: Long) = _tiles.update { list ->
        list?.map { if (it.id == id) it.copy(size = it.size.next()) else it }
    }

    /** Moves tile [id] into the slot of [targetId]; the target and the tiles between shift by one. */
    fun move(id: Long, targetId: Long) = _tiles.update { list ->
        if (list == null) return@update null
        val from = list.indexOfFirst { it.id == id }
        val to = list.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return@update list
        list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun removePackage(packageName: String, userSerial: Long) = _tiles.update { list ->
        list?.filterNot { it.app.packageName == packageName && it.app.userSerial == userSerial }
    }

    private fun encode(tiles: List<TileSpec>): String = JSONArray().apply {
        tiles.forEach { tile ->
            put(JSONObject().put("id", tile.id).put("app", tile.app.serialize()).put("size", tile.size.name))
        }
    }.toString()

    private fun decode(json: String): List<TileSpec> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            val app = AppKey.parse(obj.getString("app")) ?: return@mapNotNull null
            val size = runCatching { TileSize.valueOf(obj.getString("size")) }.getOrDefault(TileSize.MEDIUM)
            TileSpec(obj.getLong("id"), app, size)
        }
    }.getOrDefault(emptyList())
}
