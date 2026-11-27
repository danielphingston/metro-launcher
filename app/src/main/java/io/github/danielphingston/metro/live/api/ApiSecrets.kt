package io.github.danielphingston.metro.live.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The values of an API tile's headers (API keys, tokens), by tile. The layout keeps only the
 * header names; the values stay on this phone, encrypted, and are never backed up, exported or
 * shown again in full. Behind an interface so tests use [InMemoryApiSecrets].
 */
interface ApiSecrets {
    /** [tile]'s header values by name; empty when it has none (or after a restore to another phone). */
    fun get(tile: Long): Map<String, String>

    fun put(tile: Long, headers: Map<String, String>)

    fun remove(tile: Long)

    companion object {
        @Volatile
        internal var override: ApiSecrets? = null

        @Volatile
        private var shared: ApiSecrets? = null

        fun of(context: Context): ApiSecrets = override ?: shared ?: synchronized(this) {
            shared ?: KeystoreApiSecrets(context.applicationContext).also { shared = it }
        }
    }
}

class InMemoryApiSecrets : ApiSecrets {
    private val values = java.util.concurrent.ConcurrentHashMap<Long, Map<String, String>>()
    override fun get(tile: Long) = values[tile].orEmpty()
    override fun put(tile: Long, headers: Map<String, String>) {
        if (headers.isEmpty()) values.remove(tile) else values[tile] = headers
    }
    override fun remove(tile: Long) {
        values.remove(tile)
    }
}

/**
 * [ApiSecrets] in one small file in noBackupFilesDir, each tile's values sealed with AES-GCM under
 * a key that lives in the Android Keystore and can't leave the phone. A file that can't be opened
 * (a new phone, a reset keystore) reads as empty: the user enters the key again.
 */
internal class KeystoreApiSecrets(private val context: Context) : ApiSecrets {
    private val file get() = File(context.noBackupFilesDir, "api_headers.json")
    private val lock = Any()

    override fun get(tile: Long): Map<String, String> = synchronized(lock) {
        runCatching {
            val sealed = read().optString(tile.toString()).ifEmpty { return emptyMap() }
            val obj = JSONObject(open(sealed))
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    // A keystore that can't seal (a reset, a broken one) leaves the key unsaved: the tile then
    // shows "refused access" and the editor can try again, instead of the launcher crashing.
    override fun put(tile: Long, headers: Map<String, String>) = synchronized(lock) {
        runCatching {
            val all = read()
            if (headers.isEmpty()) all.remove(tile.toString()) else all.put(tile.toString(), seal(JSONObject(headers).toString()))
            write(all)
        }
        Unit
    }

    override fun remove(tile: Long) = synchronized(lock) {
        runCatching {
            val all = read()
            if (all.remove(tile.toString()) != null) write(all)
        }
        Unit
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun write(all: JSONObject) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(all.toString())
        tmp.renameTo(file)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private companion object {
        const val ALIAS = "metro_api_tile_headers"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
