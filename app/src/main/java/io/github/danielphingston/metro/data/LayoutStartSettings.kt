package io.github.danielphingston.metro.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import org.json.JSONObject

/**
 * The settings each saved start layout keeps for itself: everything the settings "start" page
 * edits (tiles per row, the paddings, accent arrows, the background and its photo, tile colour)
 * plus the accent-from-photo switch that belongs to the background.
 *
 * Like the tiles, the live keys always hold the ACTIVE layout's values, so nothing that reads
 * settings knows about layouts. A layout entry keeps the stored values of the keys that are set
 * (a key that is missing means "the default"); see [LayoutOps] for when they are written back and
 * loaded. Values keep their exact stored type (int, boolean or string). Pure.
 */
internal object LayoutStartSettings {
    /** A per-layout key and the one type its value may have. */
    sealed class Spec<T : Any>(val key: Preferences.Key<T>) {
        val name: String get() = key.name

        /** [value] as this key's type, or null when it has another type. */
        abstract fun coerce(value: Any?): T?

        fun set(prefs: MutablePreferences, value: Any) {
            coerce(value)?.let { prefs[key] = it }
        }
    }

    class IntSpec(key: Preferences.Key<Int>) : Spec<Int>(key) {
        override fun coerce(value: Any?): Int? = when (value) {
            is Int -> value
            // JSON numbers can come back as Long; only whole numbers that fit an Int count.
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
    }

    class BooleanSpec(key: Preferences.Key<Boolean>) : Spec<Boolean>(key) {
        override fun coerce(value: Any?): Boolean? = value as? Boolean
    }

    class StringSpec(key: Preferences.Key<String>) : Spec<String>(key) {
        override fun coerce(value: Any?): String? = value as? String
    }

    private val keys = SettingsRepository.Keys

    /** The per-layout keys, in the order they are written. */
    val SPECS: List<Spec<*>> = listOf(
        IntSpec(keys.columns),
        IntSpec(keys.startTopPadding),
        IntSpec(keys.startBottomPadding),
        IntSpec(keys.startLeftPadding),
        IntSpec(keys.startRightPadding),
        BooleanSpec(keys.accentArrows),
        StringSpec(keys.background),
        StringSpec(keys.backgroundPhoto),
        IntSpec(keys.backgroundAccent),
        IntSpec(keys.tileTransparency),
        BooleanSpec(keys.accentFromBackground),
        BooleanSpec(keys.photoMovesWithTiles),
        StringSpec(keys.tileColourMode),
        IntSpec(keys.tileIconVividness),
    )

    private val byName: Map<String, Spec<*>> = SPECS.associateBy { it.name }

    /** The key names, e.g. for a test or a backup check. */
    val NAMES: List<String> = SPECS.map { it.name }

    /** The key that holds the background photo's URI. */
    val PHOTO: String = keys.backgroundPhoto.name

    /**
     * The per-layout values set in [prefs], by key name. Read by name and type, so a value of
     * another type under one of the keys (from a damaged backup) is left out instead of throwing.
     */
    fun read(prefs: Preferences): Map<String, Any> = buildMap {
        prefs.asMap().forEach { (key, value) ->
            val spec = byName[key.name] ?: return@forEach
            spec.coerce(value)?.let { put(spec.name, it) }
        }
    }

    /**
     * Makes the live per-layout keys in [prefs] hold exactly [values]: each value is set with its
     * key's type and every per-layout key [values] lacks is removed (it reads as its default).
     */
    fun apply(prefs: MutablePreferences, values: Map<String, Any>) {
        SPECS.forEach { spec ->
            val value = values[spec.name]?.let(spec::coerce)
            if (value != null) spec.set(prefs, value) else prefs.remove(spec.key)
        }
    }

    /** [values] as the "settings" object of a layout entry. */
    fun encode(values: Map<String, Any>): JSONObject = JSONObject().apply {
        SPECS.forEach { spec -> values[spec.name]?.let(spec::coerce)?.let { put(spec.name, it) } }
    }

    /**
     * The values in a layout entry's "settings" object, or null when the entry has none (an entry
     * written before layouts kept settings: switching to it leaves the live settings alone).
     * Unknown keys and values of the wrong type are ignored.
     */
    fun decode(json: JSONObject?): Map<String, Any>? {
        json ?: return null
        return buildMap {
            json.keys().forEach { name ->
                val spec = byName[name] ?: return@forEach
                spec.coerce(json.opt(name))?.let { put(name, it) }
            }
        }
    }

    /**
     * Whether a switch from [from] to [to] changes the tile grid's cells (the columns or the side
     * paddings), so tiles can't glide from where they were. Null [to] keeps the live settings.
     */
    fun changesGrid(from: Map<String, Any>, to: Map<String, Any>?): Boolean {
        to ?: return false
        val grid = listOf(keys.columns, keys.startLeftPadding, keys.startRightPadding).map { it.name }
        return grid.any { from[it] != to[it] }
    }

    /**
     * The background photo URIs still in use: the live one and those of the layouts not on
     * screen. The active layout's own entry doesn't count: it is stale, and the live value
     * replaces it the next time it is written. A grant on any other URI can be let go.
     */
    fun photosInUse(set: LayoutSet?, livePhoto: String?): Set<String> = buildSet {
        livePhoto?.takeIf { it.isNotEmpty() }?.let(::add)
        set?.layouts?.filter { it.id != set.active }?.forEach { layout ->
            (layout.settings?.get(PHOTO) as? String)?.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}
