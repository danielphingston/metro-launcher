package io.github.danielphingston.metro.live.api

import android.content.Context
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.live.feed.HttpStatusException
import io.github.danielphingston.metro.live.feed.TooLargeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One request an API tile makes: an https [url] and its [headers] (an API key, say). */
data class ApiRequest(val url: String, val headers: Map<String, String> = emptyMap()) {
    /** Names the cached copy; header values count (a different key may get a different answer) but are hashed, never stored. */
    val key: String by lazy { ApiStore.hash(url + "\n" + headers.toSortedMap().entries.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }) }

    override fun toString() = "ApiRequest($url, ${headers.keys})"
}

/** Why an API couldn't be read. */
enum class ApiProblem(val short: String) {
    NOT_FOUND("not found"), REFUSED("refused access"), BUSY("busy"), SERVER("server problem"), OFFLINE("no connection"),
    INSECURE("not secure"), TOO_LARGE("too large"), NOT_JSON("not JSON"), BAD_ADDRESS("bad address"),
    ;

    fun message(code: Int? = null): String = when (this) {
        NOT_FOUND -> "nothing at that address" + (code?.let { " ($it)" } ?: "")
        REFUSED -> "the API refused access" + (code?.let { " ($it): check its key" } ?: "")
        BUSY -> "the API is busy or rate limited, try again in a minute"
        SERVER -> "the API has a problem" + (code?.let { " ($it)" } ?: "")
        OFFLINE -> "couldn't connect, check your connection"
        INSECURE -> "couldn't make a secure connection"
        TOO_LARGE -> "the answer is too large (over 1 MB)"
        NOT_JSON -> "that address doesn't answer with JSON"
        BAD_ADDRESS -> "that isn't an https address"
    }
}

/** A download's answer. [notModified]: 304, the copy held is still current. */
internal class ApiResponse(
    val bytes: ByteArray,
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false,
)

/** The network, replaceable in tests. Throws [HttpStatusException] for an error status, [TooLargeException] past [maxBytes]. */
internal fun interface ApiHttp {
    fun get(url: String, headers: Map<String, String>, maxBytes: Int): ApiResponse
}

/**
 * The last answer to a request. [root] is the parsed JSON (never touched by the UI thread while
 * being parsed); [fetchedAt] when it last came or was confirmed current; [failedAt] and [error]
 * the latest failure, shown as "stale" on the tile while [root] is still the old data.
 */
data class CachedApi(
    val key: String,
    val root: Any? = null,
    val body: String? = null,
    val fetchedAt: Long = 0,
    val failedAt: Long = 0,
    val error: ApiProblem? = null,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val hasData: Boolean get() = body != null
    val failing: Boolean get() = error != null
}

/** What [ApiStore.check] found. */
sealed interface ApiCheck {
    data class Ok(val root: Any?) : ApiCheck
    data class Failed(val problem: ApiProblem, val code: Int? = null) : ApiCheck {
        val message: String get() = problem.message(code)
    }
}

/**
 * Downloads the JSON for API tiles and keeps the last answer to each request, in memory and in
 * noBackupFilesDir (never backed up or transferred). There is no background work at all: tiles
 * ask for a refresh only while Start is on screen ([ApiRefresh]). Answers are capped at
 * [MAX_BYTES] and parsed off the main thread; each request sends If-None-Match /
 * If-Modified-Since, so an unchanged answer costs a few hundred bytes.
 */
object ApiStore {
    const val MAX_BYTES = 1024 * 1024

    private val _data = MutableStateFlow<Map<String, CachedApi>>(emptyMap())
    val data: StateFlow<Map<String, CachedApi>> = _data.asStateFlow()

    internal var http: ApiHttp = ApiHttp(::download)
    internal var downloadLimitMs = TimeUnit.SECONDS.toMillis(30)

    private val lock = Mutex()
    private val inFlight = HashSet<String>()

    /** The address each request last fetched: one with formulas ({random(1, 1025)}) can change between refreshes. */
    private val fetchedFrom = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Whether [cached] is older than [maxAgeMs]; after a failure, waits [retryMs] before the next try. */
    fun isStale(cached: CachedApi?, maxAgeMs: Long, now: Long = System.currentTimeMillis(), retryMs: Long = maxAgeMs): Boolean = when {
        cached == null -> true
        cached.failing && now - cached.failedAt < minOf(retryMs, maxAgeMs) -> false
        else -> !cached.hasData || now - cached.fetchedAt >= maxAgeMs
    }

    /** Loads the saved copy of [request] (once), then downloads it if it is older than [maxAgeMs] or [force]. */
    suspend fun refresh(context: Context, request: ApiRequest, maxAgeMs: Long, force: Boolean = false, retryMs: Long = maxAgeMs) {
        load(context, request)
        if (!force && !isStale(_data.value[request.key], maxAgeMs, retryMs = retryMs)) return
        fetch(context.applicationContext, request)
    }

    /** The saved copy of [request] into memory, if it isn't there yet. */
    suspend fun load(context: Context, request: ApiRequest) {
        if (request.key in _data.value) return
        val saved = withContext(Dispatchers.IO) { readSaved(context.applicationContext, request.key) }
        _data.update { if (request.key in it) it else it + (request.key to (saved ?: CachedApi(request.key))) }
    }

    private suspend fun fetch(app: Context, request: ApiRequest) {
        lock.withLock { if (!inFlight.add(request.key)) return }
        try {
            val held = _data.value[request.key]
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = ApiAddress.fetchAddress(request.url, now) ?: throw BadAddressException()
                    // A copy from another address (yesterday's Pokémon) says nothing about this one.
                    val same = !ApiAddress.usesFields(request.url) || fetchedFrom[request.key] == url
                    val conditional = buildMap {
                        putAll(request.headers)
                        if (held?.hasData == true && same) {
                            held.etag?.let { put("If-None-Match", it) }
                            held.lastModified?.let { put("If-Modified-Since", it) }
                        }
                    }
                    val response = http.get(url, conditional, MAX_BYTES)
                    if (response.notModified && held?.hasData == true && same) {
                        held.copy(fetchedAt = now, error = null)
                    } else {
                        val body = response.bytes.toString(Charsets.UTF_8)
                        CachedApi(request.key, ApiJson.parse(body), body, now, etag = response.etag, lastModified = response.lastModified)
                            .also { fetchedFrom[request.key] = url }
                    }
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val cached = result.getOrElse { e ->
                (held ?: CachedApi(request.key)).copy(failedAt = now, error = classify(e))
            }
            _data.update { it + (request.key to cached) }
            if (result.isSuccess) withContext(Dispatchers.IO) { runCatching { save(app, cached) } }
        } finally {
            lock.withLock { inFlight.remove(request.key) }
        }
    }

    /** Downloads [request] for the editor, so the user can pick fields from a real answer. A good answer is kept for the tile. */
    suspend fun check(context: Context, request: ApiRequest): ApiCheck = withContext(Dispatchers.IO) {
        val url = ApiAddress.fetchAddress(request.url, System.currentTimeMillis()) ?: return@withContext ApiCheck.Failed(ApiProblem.BAD_ADDRESS)
        try {
            val response = http.get(url, request.headers, MAX_BYTES)
            val body = response.bytes.toString(Charsets.UTF_8)
            val root = ApiJson.parse(body)
            val cached = CachedApi(request.key, root, body, System.currentTimeMillis(), etag = response.etag, lastModified = response.lastModified)
            _data.update { it + (request.key to cached) }
            fetchedFrom[request.key] = url
            runCatching { save(context.applicationContext, cached) }
            ApiCheck.Ok(root)
        } catch (e: HttpStatusException) {
            ApiCheck.Failed(classify(e), e.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Anything at all (an answer too big for memory, a parser that gave up) is a message, never a crash.
            ApiCheck.Failed(classify(e))
        }
    }

    internal fun classify(e: Throwable?): ApiProblem = when (e) {
        is HttpStatusException -> when (e.code) {
            400, 404, 410 -> ApiProblem.NOT_FOUND
            401, 403, 451 -> ApiProblem.REFUSED
            429 -> ApiProblem.BUSY
            in 500..599 -> ApiProblem.SERVER
            else -> ApiProblem.NOT_FOUND
        }
        is TooLargeException -> ApiProblem.TOO_LARGE
        is javax.net.ssl.SSLException -> ApiProblem.INSECURE
        is JSONException -> ApiProblem.NOT_JSON
        is BadAddressException -> ApiProblem.BAD_ADDRESS
        is OutOfMemoryError -> ApiProblem.TOO_LARGE
        is StackOverflowError -> ApiProblem.NOT_JSON
        else -> ApiProblem.OFFLINE
    }

    /**
     * [input] as an API address: https only (plain http is upgraded, as Android refuses it anyway),
     * with a host, no spaces and no user name or password; null for anything else.
     */
    fun address(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_URL || text.any { it.isWhitespace() }) return null
        val secure = when {
            text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring("http://".length)
            text.contains("://") -> return null
            else -> "https://$text"
        }
        val clean = FeedAddress.withoutCredentials(secure)
        val url = runCatching { URL(clean) }.getOrNull() ?: return null
        return clean.takeIf { url.protocol == "https" && !url.host.isNullOrEmpty() }
    }

    const val MAX_URL = 2048

    class BadAddressException : IOException("not https")

    /**
     * GETs [url] with [headers]. Follows up to 5 https redirects; the custom headers (an API key)
     * go only to the host the user typed, never to another host a redirect names. The whole
     * download, redirects included, must finish within [downloadLimitMs].
     */
    internal fun download(url: String, headers: Map<String, String>, maxBytes: Int): ApiResponse {
        var target = URL(address(url) ?: throw BadAddressException())
        val host = target.host
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(downloadLimitMs)
        repeat(5) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("download took too long")
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json, */*;q=0.5")
                if (target.host.equals(host, ignoreCase = true)) headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                val code = connection.responseCode
                if (code == 304) return ApiResponse(ByteArray(0), notModified = true)
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location") ?: throw HttpStatusException(code)
                    target = URL(target, next)
                    if (target.protocol != "https") throw BadAddressException()
                    return@repeat
                }
                if (code !in 200..299) throw HttpStatusException(code)
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw TooLargeException()
                val bytes = connection.inputStream.use { readLimited(it, maxBytes, deadline) }
                return ApiResponse(bytes, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    internal val USER_AGENT = "android:${io.github.danielphingston.metro.BuildConfig.APPLICATION_ID} (Metro launcher API tile)"

    private fun readLimited(input: InputStream, maxBytes: Int, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("download took too long")
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw TooLargeException()
        }
        return out.toByteArray()
    }

    internal fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(context: Context) = File(context.noBackupFilesDir, "api")

    private fun save(context: Context, cached: CachedApi) {
        val body = cached.body ?: return
        val dir = dir(context).apply { mkdirs() }
        val file = File(dir, cached.key + ".json")
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(
            JSONObject()
                .put("fetchedAt", cached.fetchedAt)
                .putOpt("etag", cached.etag)
                .putOpt("lastModified", cached.lastModified)
                .put("body", body)
                .toString(),
        )
        tmp.renameTo(file)
        trim(dir)
    }

    /** Keeps the newest [MAX_SAVED] answers: tiles removed long ago leave nothing behind for long. */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_SAVED).forEach { it.delete() }
    }

    private const val MAX_SAVED = 40

    private fun readSaved(context: Context, key: String): CachedApi? = runCatching {
        val file = File(dir(context), "$key.json")
        if (!file.exists() || file.length() > MAX_BYTES * 2L) return null
        val obj = JSONObject(file.readText())
        val body = obj.getString("body")
        CachedApi(
            key,
            ApiJson.parse(body),
            body,
            obj.optLong("fetchedAt"),
            etag = obj.optString("etag").ifEmpty { null },
            lastModified = obj.optString("lastModified").ifEmpty { null },
        )
    }.getOrNull()

    /** A picture for a tile, through the feed tile's picture cache (3 MB cap, web formats only, stored at tile size). https only. */
    fun image(context: Context, url: String, width: Int, height: Int): android.graphics.Bitmap? =
        if (width <= 0 || height <= 0) null else runCatching {
            address(url)?.takeIf { it == url.trim() }?.let { FeedStore.image(context, it, width, height) }
        }.getOrNull()

    /** Forgets everything held in memory; for tests. */
    internal fun clearMemory() {
        _data.value = emptyMap()
    }
}
