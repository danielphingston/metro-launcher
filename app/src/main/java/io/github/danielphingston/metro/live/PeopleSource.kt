package io.github.danielphingston.metro.live

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.LruCache
import io.github.danielphingston.metro.data.trimTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Contact photos for the people tile. Read on demand; no observers, nothing leaves the device. */
object PeopleSource {
    const val MAX_CONTACTS = 36

    private const val CACHE_BYTES = 4 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Gives memory back, keeping the [keep] (0..1) share of the photos used last. */
    fun trimMemory(keep: Float) = cache.trimTo(keep)

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Thumbnail URIs of contacts that have a photo, starred first. Without permission the
     * result is empty. (The times-contacted columns are reset to 0 by the system on Android 10+,
     * so name order is the tie-breaker.)
     */
    suspend fun photoUris(context: Context): List<String> {
        if (!hasPermission(context)) return emptyList()
        return withContext(Dispatchers.IO) {
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${ContactsContract.Contacts.PHOTO_THUMBNAIL_URI} IS NOT NULL",
                )
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${ContactsContract.Contacts.STARRED} DESC, " +
                        "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE LOCALIZED ASC",
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_CONTACTS)
            }
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                    args,
                    null,
                )?.use { cursor ->
                    buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
                }
            }.getOrNull().orEmpty()
        }
    }

    /** A thumbnail sized for a [px] square, from the cache when possible. Null if it can't be read. */
    suspend fun thumbnail(context: Context, uri: String, px: Int): Bitmap? {
        val key = "$uri#$px"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { ImageDecode.decode(context, Uri.parse(uri), px, px) }.getOrNull()
                ?.also { cache.put(key, it) }
        }
    }

    /**
     * The photo of the contact at [contactUri] (a lookup URI, which survives a contacts sync) sized
     * for a [px] square; null without permission, without a photo, or once the contact is gone.
     */
    suspend fun contactPhoto(context: Context, contactUri: String, px: Int): Bitmap? {
        if (!hasPermission(context)) return null
        val photo = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(Uri.parse(contactUri), arrayOf(ContactsContract.Contacts.PHOTO_URI), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        } ?: return null
        return thumbnail(context, photo, px)
    }
}
