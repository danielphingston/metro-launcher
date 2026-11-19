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

/** Why a feed couldn't be read. [retryable]: it may work later, so the user may add it anyway. */
enum class FeedProblem(val retryable: Boolean) {
    NOT_FOUND(false), REFUSED(false), BUSY(true), SERVER(true), OFFLINE(true), INSECURE(false),
    TOO_LARGE(false), WEB_PAGE(false), NOT_A_FEED(false), EMPTY(true), BAD_ADDRESS(false),
    ;

    /** For a feed address; [code] is the HTTP status where there was one. */
    fun message(code: Int? = null): String = when (this) {
        NOT_FOUND -> "nothing at that address" + (code?.let { " ($it)" } ?: "")
        REFUSED -> "the site refused access" + (code?.let { " ($it)" } ?: "")
        BUSY -> "the site is busy, try again in a minute"
        SERVER -> "the site has a problem" + (code?.let { " ($it)" } ?: "")
        OFFLINE -> "couldn't connect, check your connection"
        INSECURE -> "couldn't make a secure connection to the site"
        TOO_LARGE -> "the feed is too large (over 2 MB)"
        WEB_PAGE -> "that's a web page without a feed"
        NOT_A_FEED -> "that isn't an RSS or Atom feed"
        EMPTY -> "the feed has no posts"
        BAD_ADDRESS -> "that isn't a web address"
    }

    /** For a Lemmy [community]. */
    fun communityMessage(community: FeedAddress.Community): String = when (this) {
        // Lemmy answers 400 "CouldntFindCommunity" for a name it doesn't host.
        NOT_FOUND -> "$community wasn't found on ${community.instance}"
        REFUSED -> "${community.instance} refused access to $community"
        EMPTY -> "$community has no posts"
        BUSY -> "${community.instance} is busy, try again in a minute"
        SERVER -> "${community.instance} has a problem, try again later"
        OFFLINE -> "couldn't reach ${community.instance}, check your connection"
        WEB_PAGE, NOT_A_FEED -> "${community.instance} doesn't look like a Lemmy instance"
        else -> message()
    }

    /** The tile's short text after a failed refresh. */
    val short: String
        get() = when (this) {
            NOT_FOUND -> "feed not found"
            REFUSED -> "feed refused access"
            OFFLINE -> "no connection"
            EMPTY -> "nothing new"
            BUSY, SERVER -> "feed unavailable"
            else -> "not a feed"
        }
}

/** What [FeedStore.check] found. */
sealed interface FeedCheck {
    /** [url] may differ from the one checked: the feed a web page pointed to. */
    data class Ok(val url: String, val feed: Feed) : FeedCheck
    data class Failed(val problem: FeedProblem, val code: Int? = null) : FeedCheck {
        val message: String get() = problem.message(code)
    }
}

/** A downloaded body, the address it finally came from (after redirects) and its type. */
internal class Fetched(val bytes: ByteArray, val finalUrl: String, val contentType: String?)

/** The network, replaceable in tests. Throws [HttpStatusException] for a non-2xx answer. */
internal fun interface FeedHttp {
    fun get(url: String, maxBytes: Int, accept: String): Fetched
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")
class TooLargeException : IOException("too large")

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

    internal var http: FeedHttp = FeedHttp(::download)

    private const val FEED_ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"
    private val alternate = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

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
                runCatching { FeedParser.parse(http.get(url, MAX_FEED_BYTES, FEED_ACCEPT).bytes.toString(Charsets.UTF_8)) }
            }
            val feed = result.getOrNull()
            val cached = if (feed != null) {
                CachedFeed(url, feed, fetchedAt = now)
            } else {
                // Keep the headlines we had; just remember the failure so it isn't retried every resume.
                (_feeds.value[url] ?: CachedFeed(url, null)).copy(failedAt = now, error = classify(result.exceptionOrNull()).short)
            }
            _feeds.update { it + (url to cached) }
            if (feed != null) withContext(Dispatchers.IO) { runCatching { save(app, cached) } }
        } finally {
            lock.withLock { inFlight.remove(url) }
        }
    }

    /** What went wrong, from a download or parse failure. */
    internal fun classify(e: Throwable?): FeedProblem = when (e) {
        is HttpStatusException -> when (e.code) {
            400, 404, 410 -> FeedProblem.NOT_FOUND
            401, 403, 451 -> FeedProblem.REFUSED
            429 -> FeedProblem.BUSY
            in 500..599 -> FeedProblem.SERVER
            else -> FeedProblem.NOT_FOUND
        }
        is TooLargeException -> FeedProblem.TOO_LARGE
        is javax.net.ssl.SSLException -> FeedProblem.INSECURE
        is FeedFormatException, is org.xmlpull.v1.XmlPullParserException -> FeedProblem.NOT_A_FEED
        else -> FeedProblem.OFFLINE
    }

    /**
     * Downloads and reads [url] to tell whether it is a feed worth a tile, the way the user
     * would see it: an error names the problem (see [FeedProblem]). A web page that names its
     * feed (a <link rel="alternate"> to RSS or Atom) leads to that feed. A good result is kept,
     * so the new tile shows it at once. Blocking work runs on the IO dispatcher.
     */
    suspend fun check(context: Context, url: String, keep: Boolean = true): FeedCheck = withContext(Dispatchers.IO) {
        val result = checkNow(url)
        if (keep && result is FeedCheck.Ok) {
            val cached = CachedFeed(result.url, result.feed, fetchedAt = System.currentTimeMillis())
            _feeds.update { it + (result.url to cached) }
            runCatching { save(context.applicationContext, cached) }
        }
        result
    }

    /** [followPage]: a web page may still lead to its feed (once, so two pages can't send each other round). */
    private fun checkNow(url: String, followPage: Boolean = true): FeedCheck {
        val fetched = try {
            http.get(url, MAX_FEED_BYTES, FEED_ACCEPT)
        } catch (e: HttpStatusException) {
            return FeedCheck.Failed(classify(e), e.code)
        } catch (e: Exception) {
            return FeedCheck.Failed(classify(e))
        }
        val text = fetched.bytes.toString(Charsets.UTF_8)
        val feed = try {
            FeedParser.parse(text)
        } catch (e: Exception) {
            if (!looksLikeHtml(text, fetched.contentType)) return FeedCheck.Failed(FeedProblem.NOT_A_FEED)
            val found = discover(text, fetched.finalUrl)?.takeIf { followPage && it != url } ?: return FeedCheck.Failed(FeedProblem.WEB_PAGE)
            // The page named a feed: that one is checked, and a failure says what is wrong with it.
            return checkNow(found, followPage = false)
        }
        if (feed.items.isEmpty()) return FeedCheck.Failed(FeedProblem.EMPTY)
        return FeedCheck.Ok(url, feed)
    }

    private fun looksLikeHtml(text: String, contentType: String?): Boolean =
        contentType?.contains("html", ignoreCase = true) == true ||
            text.trimStart().take(512).let { it.startsWith("<!doctype html", ignoreCase = true) || it.contains("<html", ignoreCase = true) }

    /** The first RSS or Atom feed a web page names, resolved against [base]; https only. */
    internal fun discover(html: String, base: String): String? {
        for (tag in alternate.findAll(html)) {
            val attrs = attribute.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value.orEmpty())
            }
            val rel = attrs["rel"]?.lowercase()?.split(' ').orEmpty()
            val type = attrs["type"]?.lowercase().orEmpty()
            val href = attrs["href"]?.let(FeedParser::unescape) ?: continue
            if ("alternate" !in rel || (type != "application/rss+xml" && type != "application/atom+xml")) continue
            val resolved = runCatching { URL(URL(base), href).toString() }.getOrNull() ?: continue
            val secure = if (resolved.startsWith("http://")) "https://" + resolved.removePrefix("http://") else resolved
            if (secure.startsWith("https://")) return secure
        }
        return null
    }

    /**
     * The picture at [url], decoded to cover [width]x[height] pixels; downloaded once into the
     * cache dir. Null when it can't be had. Blocking: call off the main thread.
     */
    fun image(context: Context, url: String, width: Int, height: Int, declaredBytes: Long? = null): Bitmap? {
        if (url in failedImages) return null
        val fetch = sizedImageUrl(url, width)
        // Declared too big and the site can't be asked for a smaller one: not even started.
        if (fetch == url && declaredBytes != null && declaredBytes > MAX_IMAGE_BYTES) {
            failedImages += url
            return null
        }
        return runCatching {
            val dir = File(context.cacheDir, "feed_images").apply { mkdirs() }
            val file = File(dir, hash(fetch))
            if (!file.exists()) {
                val bytes = http.get(fetch, MAX_IMAGE_BYTES, "image/*").bytes
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
                trim(dir)
            }
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (w, h) = ImageDecode.coverSize(info.size.width, info.size.height, width.coerceAtLeast(1), height.coerceAtLeast(1))
                decoder.setTargetSize(w, h)
            }
        }.onFailure { failedImages += url }.getOrNull()
    }

    /**
     * Pictures that couldn't be had (too big, gone, not a picture): not tried again until the app
     * restarts, so a headline coming round again doesn't download the same failure each time.
     */
    private val failedImages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The address to download [url] from for a tile [width] pixels wide. WordPress sites (their
     * pictures live under /wp-content/uploads/) resize on request with "?w=": nasa.gov answers a
     * 27 MB original with about 140 KB. Sites that can't, ignore it. Widths are rounded up to
     * steps of 200 so tiles of similar sizes share one download. Other addresses, and ones that
     * already have a query, are left as they are.
     */
    internal fun sizedImageUrl(url: String, width: Int): String {
        if ("/wp-content/uploads/" !in url || '?' in url || '#' in url) return url
        val step = ((width.coerceIn(200, 1600) + 199) / 200) * 200
        return "$url?w=$step"
    }

    /** Keeps the picture cache to the newest [MAX_CACHED_IMAGES] files. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_IMAGES).forEach { it.delete() }
    }

    private fun download(url: String, maxBytes: Int, accept: String): Fetched {
        var target = URL(url)
        // Follows https redirects only (HttpURLConnection won't change protocol by itself anyway).
        repeat(5) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                // A descriptive agent, as feed hosts (Lemmy instances included) ask of automated clients.
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
                val bytes = connection.inputStream.use { readLimited(it, maxBytes) }
                return Fetched(bytes, target.toString(), connection.contentType)
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
            if (out.size() > maxBytes) throw TooLargeException()
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
                            item.points?.let { put("points", it) }
                            item.comments?.let { put("comments", it) }
                            item.authorHome?.let { put("authorHome", it) }
                            item.imageBytes?.let { put("imageBytes", it) }
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
                if (o.has("points")) o.optInt("points") else null,
                if (o.has("comments")) o.optInt("comments") else null,
                o.optString("authorHome").ifEmpty { null },
                if (o.has("imageBytes")) o.optLong("imageBytes") else null,
            )
        }
        CachedFeed(obj.getString("url"), Feed(obj.optString("title").ifEmpty { null }, items), obj.optLong("fetchedAt"))
    }.getOrNull()

    private val postLink = Regex("^https://([A-Za-z0-9.-]+)/post/(\\d+)/?$")
    private val canonical = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lookups = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()

    /** What a Lemmy instance says about one of its post links, asked the federation way. */
    internal sealed interface Federated {
        /** A copy of a post written elsewhere: [url] is its own address. */
        data class Moved(val url: String) : Federated

        /** Written there: the link is its own address. */
        data object Here : Federated

        /** No usable answer (an older server, an error, no connection). */
        data object Unknown : Federated
    }

    /** The federation lookup, replaceable in tests. Blocking. */
    internal var federation: (String) -> Federated = ::askFederation

    /**
     * Asks for [link] as ActivityPub, headers only: Lemmy answers a copy of a post written
     * elsewhere with a redirect to its own address, and a post written there with 200. No body
     * is downloaded and the redirect is not followed.
     */
    private fun askFederation(link: String): Federated {
        val connection = runCatching { URL(link).openConnection() as HttpURLConnection }.getOrNull() ?: return Federated.Unknown
        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "android:${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_NAME} (Metro launcher feed tile)")
            connection.setRequestProperty("Accept", "application/activity+json")
            val code = connection.responseCode
            when {
                code in 300..399 -> connection.getHeaderField("Location")
                    ?.let { runCatching { URL(URL(link), it).toString() }.getOrNull() }
                    ?.takeIf { it.startsWith("https://") }
                    ?.let { Federated.Moved(it) } ?: Federated.Unknown
                code in 200..299 -> Federated.Here
                else -> Federated.Unknown
            }
        } catch (_: Exception) {
            Federated.Unknown
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The address a Lemmy post has where it was written, for a [link] to it on the instance whose
     * feed listed it: "https://programming.dev/post/59546847" is that instance's copy of
     * "https://lemmy.today/post/63466711", the only one a Lemmy app signed in on a third instance
     * can look up. When [authorHome], the author's instance, is the link's own, the link is the
     * address and nothing is asked. Otherwise the instance is asked once, the federation way
     * (headers only); the answer is remembered and a lookup already under way is shared. Null
     * when [link] is no Lemmy post link or the instance gave no usable answer.
     */
    suspend fun canonicalPostLink(link: String, authorHome: String? = null): String? {
        canonical[link]?.let { return it }
        val m = postLink.matchEntire(link) ?: return null
        val host = m.groupValues[1].lowercase()
        if (authorHome != null && authorHome.equals(host, ignoreCase = true)) {
            canonical[link] = link
            return link
        }
        val mine = kotlinx.coroutines.CompletableDeferred<String?>()
        val running = lookups.putIfAbsent(link, mine)
        if (running != null) return running.await()
        try {
            val found = withContext(Dispatchers.IO) {
                when (val answer = federation(link)) {
                    is Federated.Moved -> answer.url
                    Federated.Here -> link
                    Federated.Unknown -> null
                }
            }
            if (found != null) canonical[link] = found
            mine.complete(found)
            return found
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            lookups.remove(link, mine)
        }
    }

    /** Whether [link]'s address is already known, so a tap needs no lookup. */
    fun knowsCanonical(link: String): Boolean = canonical.containsKey(link)

    /** Forgets everything held in memory; for tests. */
    internal fun clearMemory() {
        _feeds.value = emptyMap()
        canonical.clear()
        lookups.clear()
        failedImages.clear()
    }
}
