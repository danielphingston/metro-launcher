package io.github.danielphingston.metro.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.danielphingston.metro.BuildConfig
import io.github.danielphingston.metro.MetroApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A backup could not be read; [message] is written for the person using the app. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Export and import of the launcher's tiles and settings as one JSON file.
 *
 * ```
 * {"app":"metro-launcher","format":1,"appVersion":"0.7.16","exportedAt":1700000000000,
 *  "prefs":{"<key>":{"type":"string|int|long|boolean|float|double|stringSet","value":...}}}
 * ```
 *
 * The file mirrors the "metro" DataStore entry for entry (minus [EXCLUDED_KEYS]); search history
 * and purchases live elsewhere and are deliberately not included.
 *
 * COMPATIBILITY: every [FORMAT] ever shipped must stay importable forever. When anything stored
 * changes, either keep reading the old shape or bump [FORMAT] and add one branch to [migrate].
 * The permanent fixtures `src/test/resources/backup/format-N.json` (never edit or delete one)
 * are decoded by BackupTest to enforce this.
 */
object Backup {
    /** The format this build writes and the newest it can read. */
    const val FORMAT = 1

    private const val APP_ID = "metro-launcher"

    /** Refuse absurdly large files before reading them all into memory. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * Keys that are never exported and are ignored on import (the current value stays): the
     * downloaded forecast and its timestamp are cache that refetches itself, and the last
     * assistant question is private, short-lived conversation state.
     */
    val EXCLUDED_KEYS: Set<String> = setOf(
        "weather_forecast",
        "weather_fetched_at",
        "assistant_last_question",
        "assistant_last_asked_at",
    )

    fun encode(prefs: Preferences, appVersion: String, now: Long): String {
        val entries = JSONObject()
        prefs.asMap().entries
            .filter { it.key.name !in EXCLUDED_KEYS }
            .sortedBy { it.key.name }
            .forEach { (key, value) -> encodeEntry(value)?.let { entries.put(key.name, it) } }
        return JSONObject()
            .put("app", APP_ID)
            .put("format", FORMAT)
            .put("appVersion", appVersion)
            .put("exportedAt", now)
            .put("prefs", entries)
            .toString(2)
    }

    private fun encodeEntry(value: Any): JSONObject? {
        val (type, json) = when (value) {
            is String -> "string" to value
            is Int -> "int" to value
            is Long -> "long" to value
            is Boolean -> "boolean" to value
            is Float -> "float" to value.toDouble()
            is Double -> "double" to value
            is Set<*> -> "stringSet" to JSONArray().also { array -> value.sortedBy { it.toString() }.forEach { array.put(it.toString()) } }
            else -> return null
        }
        if (json is Double && !json.isFinite()) return null // JSON has no NaN or infinity.
        return JSONObject().put("type", type).put("value", json)
    }

    /** The preferences in [json], without [EXCLUDED_KEYS]. Throws [BackupException] for anything unusable. */
    fun decode(json: String): Preferences {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupException("This file is not a Metro backup.", e)
        }
        if (root.optString("app") != APP_ID) throw BackupException("This file is not a Metro backup.")
        val format = root.optInt("format", 0)
        if (format < 1) throw BackupException("This file is not a Metro backup.")
        if (format > FORMAT) throw BackupException("This backup is from a newer version of Metro. Update the app to restore it.")
        val migrated = migrate(format, root)
        val entries = migrated.optJSONObject("prefs") ?: throw BackupException("This backup is damaged: it has no settings in it.")
        val result = mutablePreferencesOf()
        for (name in entries.keys()) {
            if (name in EXCLUDED_KEYS) continue
            val entry = entries.optJSONObject(name) ?: throw malformed(name)
            try {
                when (entry.optString("type")) {
                    "string" -> result[stringPreferencesKey(name)] = entry.strictString("value")
                    "int" -> result[intPreferencesKey(name)] = entry.getInt("value")
                    "long" -> result[longPreferencesKey(name)] = entry.getLong("value")
                    "boolean" -> result[booleanPreferencesKey(name)] = entry.getBoolean("value")
                    "float" -> result[floatPreferencesKey(name)] = entry.getDouble("value").toFloat()
                    "double" -> result[doublePreferencesKey(name)] = entry.getDouble("value")
                    "stringSet" -> {
                        val array = entry.getJSONArray("value")
                        result[stringSetPreferencesKey(name)] = (0 until array.length()).mapTo(LinkedHashSet()) { array.getString(it) }
                    }
                    else -> Unit // A type from a newer build: skip the entry, keep the rest.
                }
            } catch (e: JSONException) {
                throw malformed(name, e)
            }
        }
        return result
    }

    /** At most [limit] bytes of the stream. */
    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun JSONObject.strictString(name: String): String {
        val value = get(name)
        if (value !is String) throw JSONException("not a string")
        return value
    }

    private fun malformed(name: String, cause: Throwable? = null) =
        BackupException("This backup is damaged: the entry \"$name\" can't be read.", cause)

    /**
     * Brings a backup written in [format] up to [FORMAT], one step at a time. To add format 2:
     * bump [FORMAT] and add `1 -> migrate1to2(current)` below; format 1 itself needs no change.
     */
    internal fun migrate(format: Int, root: JSONObject): JSONObject {
        var current = root
        var version = format
        while (version < FORMAT) {
            val next: JSONObject? = when (version) {
                else -> null
            }
            current = next ?: throw BackupException("This backup can't be restored by this version of Metro.")
            version++
        }
        return current
    }

    /** Writes the current tiles and settings to [uri]. */
    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val prefs = context.metroDataStore.data.first()
        val text = encode(prefs, BuildConfig.VERSION_NAME, System.currentTimeMillis())
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to that file.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Replaces the tiles and settings with the backup at [uri], in one atomic DataStore edit.
     * Nothing is touched if the file is unusable. Throws [BackupException] or [IOException].
     */
    suspend fun import(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read that file.")
        val bytes = input.use { it.readBounded(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw BackupException("This file is too large to be a Metro backup.")
        val restored = decode(bytes.toString(Charsets.UTF_8))
        // A value of the wrong type under a key the launcher reads would crash it on every start.
        try {
            SettingsRepository(context).settingsOf(restored)
        } catch (e: ClassCastException) {
            throw wrongKind(e)
        }
        checkKeyTypes(restored)
        context.metroDataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name !in EXCLUDED_KEYS }.forEach { prefs.remove(it) }
            prefs += restored
        }
        val app = context.applicationContext as MetroApplication
        app.tiles.reloadFromStore() // Tiles are held in memory; show the restored layout and stop it being overwritten.
        if (restored.asMap().keys.any { it.name == "weather_latitude" }) {
            io.github.danielphingston.metro.live.weather.WeatherRefresh.onSettingsChanged(context)
        }
    }

    /**
     * Keys read outside SettingsRepository (TileRepository, PhotoSource, WeatherStore) with the
     * type each is read as. The forecast keys are in [EXCLUDED_KEYS] and never imported.
     */
    private val EXTERNAL_KEYS: Map<String, Class<*>> = mapOf(
        "tiles" to String::class.java,
        "tiles_backup" to String::class.java,
        "tile_photos" to String::class.java,
        "weather_name" to String::class.java,
        "weather_detail" to String::class.java,
        "weather_latitude" to Double::class.javaObjectType,
        "weather_longitude" to Double::class.javaObjectType,
        "weather_fahrenheit" to Boolean::class.javaObjectType,
    )

    private fun wrongKind(cause: Throwable? = null) =
        BackupException("This backup is damaged: it holds a setting of the wrong kind.", cause)

    /** Throws [BackupException] when a key read outside SettingsRepository holds the wrong type. */
    internal fun checkKeyTypes(prefs: Preferences) {
        prefs.asMap().forEach { (key, value) ->
            val expected = EXTERNAL_KEYS[key.name] ?: return@forEach
            if (!expected.isInstance(value)) throw wrongKind()
        }
    }
}
