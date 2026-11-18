package io.github.danielphingston.metro.live.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.danielphingston.metro.BuildConfig
import io.github.danielphingston.metro.live.ImageDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The last download of a feed. [fetchedAt] epoch millis; [error] says why the latest attempt failed. */
data class CachedFeed(
    val url: String,
    val feed: Feed?,
    val fetchedAt: Long = 0,
    val failedAt: Long = 0,
    val error: String? = null,
)

/**
 * Downloads feeds for the feed tiles and keeps the last copy of each, so a tile shows its
 * headlines at once after a restart. There is no background work: a tile refreshes its feed when
 * Start is shown and the copy is older than [MAX_AGE_MS]. The copies live in noBackupFilesDir
 * (never backed up or transferred; the tile's address is in the layout, which is) and pictures in
 * the cache dir.
 */
object FeedStore {
    val MAX_AGE_MS = TimeUnit.MINUTES.toMillis(30)

    /** After a failed download, how long before the next try. */
    val RETRY_MS = TimeUnit.MINUTES.toMillis(5)
    private const val MAX_FEED_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    private const val MAX_CACHED_IMAGES = 120

    private val _feeds = MutableStateFlow<Map<String, CachedFeed>>(emptyMap())
    val feeds: StateFlow<Map<String, CachedFeed>> = _feeds.asStateFlow()

    private val lock = Mutex()
    private val inFlight = HashSet<String>()

    fun isStale(cached: CachedFeed?, now: Long = System.currentTimeMillis()): Boolean = when {
        cached == null -> true
        cached.failedAt > cached.fetchedAt && now - cached.failedAt < RETRY_MS -> false
        else -> cached.feed == null || now - cached.fetchedAt > MAX_AGE_MS
    }

    /** Loads the saved copy of [url] (once), then downloads it if it is stale or [force]. */
    suspend fun refresh(context: Context, url: String, force: Boolean = false) {
        val app = context.applicationContext
        if (url !in _feeds.value) {
            val saved = withContext(Dispatchers.IO) { readSaved(app, url) }
            _feeds.update { if (url in it) it else it + (url to (saved ?: CachedFeed(url, null))) }
        }
        if (!force && !isStale(_feeds.value[url])) return
        lock.withLock { if (!inFlight.add(url)) return }
        try {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching { FeedParser.parse(download(url, MAX_FEED_BYTES, "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8").toString(Charsets.UTF_8)) }
            }
            val feed = result.getOrNull()
            val cached = if (feed != null) {
                CachedFeed(url, feed, fetchedAt = now)
            } else {
                // Keep the headlines we had; just remember the failure so it isn't retried every resume.
                (_feeds.value[url] ?: CachedFeed(url, null)).copy(failedAt = now, error = describe(result.exceptionOrNull()))
            }
            _feeds.update { it + (url to cached) }
            if (feed != null) withContext(Dispatchers.IO) { runCatching { save(app, cached) } }
        } finally {
            lock.withLock { inFlight.remove(url) }
        }
    }

    private fun describe(e: Throwable?): String = when (e) {
        is FeedFormatException -> "not a feed"
        is HttpStatusException -> if (e.code == 404) "feed not found" else "feed unavailable (${e.code})"
        else -> "no connection"
    }

    /**
     * The picture at [url], decoded to cover [width]x[height] pixels; downloaded once into the
     * cache dir. Null when it can't be had. Blocking: call off the main thread.
     */
    fun image(context: Context, url: String, width: Int, height: Int): Bitmap? = runCatching {
        val dir = File(context.cacheDir, "feed_images").apply { mkdirs() }
        val file = File(dir, hash(url))
        if (!file.exists()) {
            val bytes = download(url, MAX_IMAGE_BYTES, "image/*")
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            trim(dir)
        }
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (w, h) = ImageDecode.coverSize(info.size.width, info.size.height, width.coerceAtLeast(1), height.coerceAtLeast(1))
            decoder.setTargetSize(w, h)
        }
    }.getOrNull()

    /** Keeps the picture cache to the newest [MAX_CACHED_IMAGES] files. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_IMAGES).forEach { it.delete() }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private fun download(url: String, maxBytes: Int, accept: String): ByteArray {
        var target = URL(url)
        // Follows https redirects only (HttpURLConnection won't change protocol by itself anyway).
        repeat(5) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                // Reddit refuses generic clients; it asks for a descriptive agent like this one.
                connection.setRequestProperty("User-Agent", "android:${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_NAME} (Metro launcher feed tile)")
                connection.setRequestProperty("Accept", accept)
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location") ?: throw HttpStatusException(code)
                    target = URL(target, next)
                    if (target.protocol != "https") throw IOException("insecure redirect")
                    return@repeat
                }
                if (code !in 200..299) throw HttpStatusException(code)
                return connection.inputStream.use { readLimited(it, maxBytes) }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw IOException("too large")
        }
        return out.toByteArray()
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(context: Context) = File(context.noBackupFilesDir, "feeds")

    private fun save(context: Context, cached: CachedFeed) {
        val dir = dir(context).apply { mkdirs() }
        val file = File(dir, hash(cached.url) + ".json")
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(encode(cached).toString())
        tmp.renameTo(file)
    }

    private fun readSaved(context: Context, url: String): CachedFeed? = runCatching {
        decode(File(dir(context), hash(url) + ".json").readText())?.takeIf { it.url == url }
    }.getOrNull()

    internal fun encode(cached: CachedFeed): JSONObject = JSONObject()
        .put("url", cached.url)
        .put("fetchedAt", cached.fetchedAt)
        .apply { cached.feed?.title?.let { put("title", it) } }
        .put(
            "items",
            JSONArray().apply {
                cached.feed?.items.orEmpty().forEach { item ->
                    put(
                        JSONObject().put("title", item.title).apply {
                            item.link?.let { put("link", it) }
                            item.author?.let { put("author", it) }
                            item.publishedAt?.let { put("publishedAt", it) }
                            item.image?.let { put("image", it) }
                        },
                    )
                }
            },
        )

    internal fun decode(text: String): CachedFeed? = runCatching {
        val obj = JSONObject(text)
        val array = obj.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").ifEmpty { return@mapNotNull null }
            FeedItem(
                title,
                o.optString("link").ifEmpty { null },
                o.optString("author").ifEmpty { null },
                if (o.has("publishedAt")) o.optLong("publishedAt") else null,
                o.optString("image").ifEmpty { null },
            )
        }
        CachedFeed(obj.getString("url"), Feed(obj.optString("title").ifEmpty { null }, items), obj.optLong("fetchedAt"))
    }.getOrNull()

    /** Forgets everything held in memory; for tests. */
    internal fun clearMemory() {
        _feeds.value = emptyMap()
    }
}
