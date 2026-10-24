package io.github.danielphingston.metro.live

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.metroDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The photos the user picked for the photos tile, as persisted URIs. Only these are readable,
 * so Metro needs no media permission.
 */
object PhotoSource {
    const val MAX_PHOTOS = 30

    private val key = stringPreferencesKey("tile_photos")

    /** URIs are stored one per line: a URI never contains a raw newline. */
    fun encode(uris: List<String>): String = uris.filter { it.isNotBlank() }.distinct().joinToString("\n")

    fun decode(encoded: String?): List<String> =
        encoded.orEmpty().lineSequence().filter { it.isNotBlank() }.distinct().take(MAX_PHOTOS).toList()

    fun uris(context: Context): Flow<List<String>> =
        context.metroDataStore.data.map { decode(it[key]) }.distinctUntilChanged()

    suspend fun current(context: Context): List<String> = uris(context).first()

    suspend fun save(context: Context, uris: List<String>) {
        context.metroDataStore.edit { it[key] = encode(uris) }
    }

    /** Drops photos that can no longer be read. */
    suspend fun remove(context: Context, dead: Collection<String>) {
        context.metroDataStore.edit { prefs -> prefs[key] = encode(decode(prefs[key]) - dead.toSet()) }
    }
}
