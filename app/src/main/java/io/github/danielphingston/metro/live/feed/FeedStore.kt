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
    /**
     * The picture cache keeps [IMAGES_PER_FEED] pictures for each feed in use (a feed lists at
     * most [FeedParser.MAX_ITEMS] posts, so all of a feed's pictures fit), between
     * [MIN_CACHED_IMAGES] and [MAX_CACHED_IMAGES] files. Pictures are stored at tile size, so even
     * the most is tens of MB.
     */
    private const val IMAGES_PER_FEED = 30
    private const val MIN_CACHED_IMAGES = 120
    private const val MAX_CACHED_IMAGES = 600

    /** A picture this small (or one no bigger than the tile) is kept as downloaded: shrinking it would gain little and cost quality. */
    internal const val KEEP_AS_IS_BYTES = 150 * 1024

    /** How long a picture in use goes before it is marked as used again, so the cache keeps it. */
    private val TOUCH_AFTER_MS = TimeUnit.HOURS.toMillis(1)

    private val _feeds = MutableStateFlow<Map<String, CachedFeed>>(emptyMap())
    val feeds: StateFlow<Map<String, CachedFeed>> = _feeds.asStateFlow()

    internal var http: FeedHttp = FeedHttp(::download)

    private const val FEED_ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"
    // Bounded, so a crafted page can't make these backtrack for hours (see FeedParser).
    private val alternate = Regex("""<link\b[^<>]{0,4096}>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([a-zA-Z-]{1,40})\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    /**
     * Sent with every feed request: who is asking, as feed hosts (Lemmy instances included) ask of
     * automated clients. Without the app's version, so a host can't single out builds to target.
     */
    internal val USER_AGENT = "android:${BuildConfig.APPLICATION_ID} (Metro launcher feed tile)"

    /**
     * The most one download (a feed or a picture, redirects included) may take in all. Each read
     * also has its own limit, but a server sending a byte every few seconds would pass those
     * forever. A minute fits a 2 MB feed on a slow connection.
     */
    internal var downloadLimitMs = TimeUnit.SECONDS.toMillis(60)

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
        val sized = sizedImageUrl(url, width, height)
        // A Lemmy server that can't make the smaller picture (an old one, a format it can't
        // convert) still has the picture itself: that is tried next, as before this was asked.
        val tries = if (sized != url && isLemmyThumbnail(sized)) listOf(sized, url) else listOf(sized)
        for (fetch in tries) {
            // Declared too big and the site can't be asked for a smaller one: not even started.
            if (fetch == url && declaredBytes != null && declaredBytes > MAX_IMAGE_BYTES) break
            load(context, fetch, width, height)?.let { return it }
        }
        failedImages += url
        return null
    }

    /** The picture downloaded from [fetch] (or its copy in the cache dir), decoded for the tile; null when it can't be had. */
    private fun load(context: Context, fetch: String, width: Int, height: Int): Bitmap? =
        runCatching {
            val dir = File(context.cacheDir, "feed_images").apply { mkdirs() }
            // Stored at the tile's size (rounded up), so each size of tile has its own copy.
            val (boxW, boxH) = storeBox(width, height)
            val file = File(dir, hash("$fetch@${boxW}x$boxH"))
            if (file.exists()) {
                touch(file)
            } else {
                val bytes = http.get(fetch, MAX_IMAGE_BYTES, "image/*").bytes
                if (!isWebImage(bytes.copyOf(minOf(bytes.size, 32)))) throw IOException("not a web picture")
                // A picture that can't be shrunk (or isn't worth it) is kept as it came.
                val stored = runCatching { shrink(bytes, boxW, boxH) }.getOrNull() ?: bytes
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeBytes(stored)
                tmp.renameTo(file)
                trim(dir)
            }
            if (!isWebImage(readHead(file))) {
                file.delete()
                throw IOException("not a web picture")
            }
            decodeFile(file, width, height)
        }.getOrNull()

    /** The size a picture is stored at for a [width] x [height] tile: rounded up to steps of 100, so close sizes share a copy. */
    internal fun storeBox(width: Int, height: Int): Pair<Int, Int> =
        ((width.coerceAtLeast(1) + 99) / 100 * 100) to ((height.coerceAtLeast(1) + 99) / 100 * 100)

    /**
     * [bytes] (a downloaded picture) shrunk to cover a [boxW] x [boxH] tile, as WebP; null to keep
     * them as they are: small already ([KEEP_AS_IS_BYTES]), no bigger than the tile, or when the
     * smaller copy wouldn't be smaller. Decoded once more on the first showing, off the main
     * thread; every later showing then decodes the small copy. Replaceable in tests.
     */
    internal var shrink: (ByteArray, Int, Int) -> ByteArray? = { bytes, boxW, boxH ->
        if (bytes.size <= KEEP_AS_IS_BYTES) {
            null
        } else {
            var smaller = false
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val target = decodeTarget(info.size.width, info.size.height, boxW, boxH)
                smaller = target.width < info.size.width || target.height < info.size.height || target.crop != null
                // Pixels in ordinary memory: a picture in graphics memory can't be compressed.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(target.width, target.height)
                target.crop?.let { decoder.crop = it }
            }
            try {
                if (!smaller) {
                    null
                } else {
                    val out = ByteArrayOutputStream(64 * 1024)
                    if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)) null
                    else out.toByteArray().takeIf { it.isNotEmpty() && it.size < bytes.size }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private const val WEBP_QUALITY = 85

    /** Marks [file] as used (at most once an hour), so the cache keeps pictures still on tiles. */
    private fun touch(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > TOUCH_AFTER_MS) file.setLastModified(now)
    }

    /** Decodes a downloaded picture for a [width] x [height] tile; replaceable in tests (Robolectric can't run ImageDecoder). */
    internal var decodeFile: (File, Int, Int) -> Bitmap = { file, width, height ->
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val target = decodeTarget(info.size.width, info.size.height, width, height)
            decoder.setTargetSize(target.width, target.height)
            target.crop?.let { decoder.crop = it }
        }
    }

    /** The size to decode a picture at, and the part of it to keep (null: all of it). */
    internal data class DecodeTarget(val width: Int, val height: Int, val crop: android.graphics.Rect?)

    /**
     * Pictures are scaled to cover the tile ([ImageDecode.coverSize]). A very long one (a 65000x400
     * panorama, a few KB as a PNG) would still cover it at full size, about 100 MB; such a picture
     * is cut to its middle, at most [MAX_COVER_FACTOR] times the tile's side, which is more than
     * the tile shows (the middle). Any ordinary picture is decoded as before, uncut.
     */
    internal fun decodeTarget(srcW: Int, srcH: Int, boxW: Int, boxH: Int): DecodeTarget {
        val bw = boxW.coerceAtLeast(1)
        val bh = boxH.coerceAtLeast(1)
        val (w, h) = ImageDecode.coverSize(srcW.coerceAtLeast(1), srcH.coerceAtLeast(1), bw, bh)
        val maxW = bw.toLong() * MAX_COVER_FACTOR
        val maxH = bh.toLong() * MAX_COVER_FACTOR
        if (w <= maxW && h <= maxH) return DecodeTarget(w, h, null)
        val cw = minOf(w.toLong(), maxW).toInt()
        val ch = minOf(h.toLong(), maxH).toInt()
        val left = (w - cw) / 2
        val top = (h - ch) / 2
        return DecodeTarget(w, h, android.graphics.Rect(left, top, left + cw, top + ch))
    }

    private const val MAX_COVER_FACTOR = 4

    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val head = ByteArray(32)
        var n = 0
        while (n < head.size) {
            val read = input.read(head, n, head.size - n)
            if (read < 0) break
            n += read
        }
        head.copyOf(n)
    }

    /**
     * Whether [head], a file's first bytes, starts a JPEG, PNG, GIF, WebP or AVIF picture, the
     * formats feeds use. Anything else isn't decoded: Android would also decode camera raw (DNG),
     * HEIF, BMP and ICO, large native decoders a stranger's feed has no need to reach.
     */
    internal fun isWebImage(head: ByteArray): Boolean {
        fun at(offset: Int, vararg bytes: Int) =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> true // JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> true // PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> true
            ascii(0, "RIFF") && ascii(8, "WEBP") -> true
            ascii(4, "ftyp") -> {
                // AVIF: an ISO box whose major brand, or a compatible one, is avif or avis.
                val boxSize = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
                    ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                val end = minOf(head.size, boxSize.coerceAtLeast(0))
                val brands = buildList {
                    if (end >= 12) add(8)
                    var i = 16
                    while (i + 4 <= end) { add(i); i += 4 }
                }
                brands.any { ascii(it, "avif") || ascii(it, "avis") }
            }
            else -> false
        }
    }

    /**
     * Pictures that couldn't be had (too big, gone, not a picture): not tried again until the app
     * restarts, so a headline coming round again doesn't download the same failure each time.
     */
    private val failedImages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The address to download [url] from for a tile [width] x [height] pixels. Sizes are rounded
     * up to steps of 200 so tiles of similar sizes share one download. Other addresses, and ones
     * that already have a query (or a fragment), are left as they are.
     *
     * - WordPress sites (pictures under /wp-content/uploads/) resize on request with "?w=":
     *   nasa.gov answers a 27 MB original with about 140 KB. Sites that can't, ignore it.
     * - Lemmy's picture server (pict-rs: /pictrs/image/..., and the /api/v3/image_proxy that
     *   lemmy.ml sends every picture through) makes a smaller WebP with "thumbnail=N&format=webp",
     *   N being the longest side; it never enlarges. As the tile shows the picture cropped to
     *   cover it, N is twice the tile's longer side, so any picture up to 2:1 still covers the
     *   tile as sharply as the original. Measured on lemmy.world: a 5.6 MB photo comes as about
     *   1 MB at 1400, a 3 MB one as about 450 KB.
     */
    internal fun sizedImageUrl(url: String, width: Int, height: Int = width): String {
        if ('#' in url) return url
        if ("/wp-content/uploads/" in url && '?' !in url) {
            val step = ((width.coerceIn(200, 1600) + 199) / 200) * 200
            return "$url?w=$step"
        }
        val side = ((2 * maxOf(width, height)).coerceIn(400, 2000) + 199) / 200 * 200
        if (pictrsImage.containsMatchIn(url) && '?' !in url) return "$url?thumbnail=$side&format=webp"
        if (imageProxy.containsMatchIn(url) && "thumbnail=" !in url) return "$url&thumbnail=$side&format=webp"
        return url
    }

    /** A picture on a Lemmy instance's own picture server, and one it passes on through its proxy. */
    private val pictrsImage = Regex("^https://[^/?#]+/pictrs/image/[^/?#]+$")
    private val imageProxy = Regex("^https://[^/?#]+/api/v3/image_proxy\\?url=[^&#]+$")

    /** Whether [fetch] is a smaller picture asked of a Lemmy server (see [sizedImageUrl]). */
    private fun isLemmyThumbnail(fetch: String): Boolean = fetch.endsWith("&format=webp") && "thumbnail=" in fetch

    /** How many pictures the cache keeps for [feeds] feeds in use; see [IMAGES_PER_FEED]. */
    internal fun cacheLimit(feeds: Int): Int = (IMAGES_PER_FEED * feeds).coerceIn(MIN_CACHED_IMAGES, MAX_CACHED_IMAGES)

    /** Keeps the picture cache to the most recently used [cacheLimit] files. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(cacheLimit(_feeds.value.size)).forEach { it.delete() }
    }

    internal fun download(url: String, maxBytes: Int, accept: String): Fetched {
        var target = URL(url)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(downloadLimitMs)
        // Follows https redirects only (HttpURLConnection won't change protocol by itself anyway).
        repeat(5) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("download took too long")
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", accept)
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location") ?: throw HttpStatusException(code)
                    target = URL(target, next)
                    if (target.protocol != "https") throw IOException("insecure redirect")
                    return@repeat
                }
                if (code !in 200..299) throw HttpStatusException(code)
                val bytes = connection.inputStream.use { readLimited(it, maxBytes, deadline) }
                return Fetched(bytes, target.toString(), connection.contentType)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

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
            // Copies saved by earlier versions may hold longer text than the parser now keeps.
            val title = o.optString("title").ifEmpty { return@mapNotNull null }.let { FeedParser.cap(it, FeedParser.MAX_TITLE) }
            FeedItem(
                title,
                o.optString("link").ifEmpty { null },
                o.optString("author").ifEmpty { null }?.let { FeedParser.cap(it, FeedParser.MAX_AUTHOR) },
                if (o.has("publishedAt")) o.optLong("publishedAt") else null,
                o.optString("image").ifEmpty { null },
                if (o.has("points")) o.optInt("points") else null,
                if (o.has("comments")) o.optInt("comments") else null,
                o.optString("authorHome").ifEmpty { null },
                if (o.has("imageBytes")) o.optLong("imageBytes") else null,
            )
        }
        val feedTitle = obj.optString("title").ifEmpty { null }?.let { FeedParser.cap(it, FeedParser.MAX_FEED_TITLE) }
        CachedFeed(obj.getString("url"), Feed(feedTitle, items), obj.optLong("fetchedAt"))
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
     * Asks for [link] as ActivityPub: Lemmy answers a copy of a post written elsewhere with a
     * redirect to its own address, and a post written there with 200. Only the status and
     * headers are read; the body (none for a redirect, about 2 KB for a post) is not, and the
     * redirect is not followed. A GET, not a HEAD: lemmy.world answers HEAD with 404.
     */
    private fun askFederation(link: String): Federated {
        val connection = runCatching { URL(link).openConnection() as HttpURLConnection }.getOrNull() ?: return Federated.Unknown
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
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
