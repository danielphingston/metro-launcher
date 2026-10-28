package io.github.danielphingston.metro.live

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.danielphingston.metro.data.AppKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.roundToInt

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val isPlaying: Boolean,
)

object MediaSource {
    /** Longest side, in pixels, that album art is downscaled to: plenty for a tile, cheap to hold. */
    private const val MAX_ART_PX = 512

    /**
     * Active media sessions keyed by [io.github.danielphingston.metro.data.AppKey.notificationKey].
     * Collected only while the start screen is visible.
     *
     * Event-driven: the system reports session, metadata and playback changes, so nothing is
     * polled. Reading sessions needs notification-listener access; without it the map is empty.
     */
    fun nowPlaying(context: Context): Flow<Map<String, NowPlaying>> = callbackFlow {
        val app = context.applicationContext
        val manager = app.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(app, MetroNotificationListener::class.java)
        val serial = UserSerials.of(app, Process.myUserHandle())
        val handler = Handler(Looper.getMainLooper())
        val lock = Any()
        var watched = emptyList<Pair<MediaController, MediaController.Callback>>()
        // Converted art by package, title, album, artist and duration, so playback-state ticks don't convert it again.
        val artCache = HashMap<String, ImageBitmap>()

        fun publish() {
            val result = synchronized(lock) {
                val playing = HashMap<String, NowPlaying>()
                val current = HashSet<String>()
                for ((controller, _) in watched) {
                    val metadata = controller.metadata ?: continue
                    val state = controller.playbackState?.state
                    val title = MediaSessionFilter.firstNotBlank(
                        metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                        metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    )
                    if (title.isEmpty() || !MediaSessionFilter.isShown(state)) continue
                    val cacheKey = MediaSessionFilter.artKey(
                        controller.packageName,
                        title,
                        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    )
                    current += cacheKey
                    playing[AppKey.notificationKey(controller.packageName, serial)] = NowPlaying(
                        title = title,
                        artist = MediaSessionFilter.firstNotBlank(
                            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                        ),
                        art = artCache[cacheKey] ?: artOf(metadata)?.also { artCache[cacheKey] = it },
                        isPlaying = state != PlaybackState.STATE_PAUSED,
                    )
                }
                artCache.keys.retainAll(current)
                playing
            }
            trySend(result)
        }

        fun adopt(sessions: List<MediaController>?) {
            synchronized(lock) {
                watched.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
                watched = sessions.orEmpty().map { controller ->
                    val callback = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                        override fun onSessionDestroyed() {
                            synchronized(lock) { watched = watched.filter { it.first !== controller } }
                            controller.unregisterCallback(this)
                            publish()
                        }
                    }
                    controller.registerCallback(callback, handler)
                    controller to callback
                }
            }
            publish()
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { adopt(it) }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            adopt(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            // No notification-listener access (yet): show nothing.
            trySend(emptyMap())
            close()
        }
        awaitClose {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
            synchronized(lock) {
                watched.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
                watched = emptyList()
                artCache.clear()
            }
        }
    }.conflate().distinctUntilChanged()

    private fun artOf(metadata: MediaMetadata): ImageBitmap? {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        // The media app owns this bitmap and may recycle it at any time, so never hand it to Compose:
        // always draw it into a bitmap of our own, bounded in size.
        if (bitmap.isRecycled) return null
        val (width, height) = MediaSessionFilter.fit(bitmap.width, bitmap.height, MAX_ART_PX)
        return runCatching {
            val copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(copy).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            copy.asImageBitmap()
        }.getOrNull()
    }
}

/** The pure decisions behind [MediaSource], free of framework objects so they can be unit tested. */
internal object MediaSessionFilter {
    /** Playback states worth a tile: a stopped or errored session is not "now playing". */
    fun isShown(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        -> true
        else -> false
    }

    /**
     * Cache key for a track's art. The title alone isn't enough: a new album with a same-named track
     * would show the old cover.
     */
    fun artKey(pkg: String, title: String, album: String?, artist: String?, durationMs: Long): String =
        listOf(pkg, title, album.orEmpty(), artist.orEmpty(), durationMs.toString()).joinToString("\u0000")

    /** The first of [candidates] that isn't blank, or an empty string. */
    fun firstNotBlank(vararg candidates: String?): String = candidates.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    /** [width] x [height] scaled down, keeping the aspect ratio, so its longest side is at most [maxPx]. */
    fun fit(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxPx) return width to height
        val scale = maxPx.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}
