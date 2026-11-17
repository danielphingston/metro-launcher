package io.github.danielphingston.metro.live.tracking

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Pictures a tracking notification carried, scaled to tile size: the driver, dish or product, and Android 16's tracker icon. */
class TrackImages(val picture: Bitmap?, val tracker: Bitmap?)

/** The live trips and orders right now, with what a tap and the tile's pictures need (keyed by notification key). */
data class TrackingSnapshot(
    val items: List<TrackItem>,
    val intents: Map<String, PendingIntent> = emptyMap(),
    val images: Map<String, TrackImages> = emptyMap(),
) {
    companion object {
        val EMPTY = TrackingSnapshot(emptyList())
    }
}

/**
 * In-memory bridge for the tracking tile, fed by the notification listener's existing rebuild.
 * Nothing here runs on its own: it changes only when a notification is posted or removed.
 */
object TrackingFeed {
    private val _snapshot = MutableStateFlow(TrackingSnapshot.EMPTY)
    val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

    internal fun publish(value: TrackingSnapshot) {
        _snapshot.value = value
    }

    internal fun clear() {
        _snapshot.value = TrackingSnapshot.EMPTY
    }
}

/**
 * Reads tracking notifications for [TrackingParser]. Another app's extras are parcelled with its
 * own classes, so every read here can throw; each one is caught and only skips what it read.
 */
internal class TrackingReader(private val context: Context) {
    /** Pictures by notification key and post time, so an unchanged notification is not decoded again. */
    private val imageCache = HashMap<String, Pair<Long, TrackImages>>()

    /** Whether [sbn] is worth reading at all: a known app, or an Android 16 Live Update the system promoted. */
    fun isCandidate(sbn: StatusBarNotification, ownPackage: String): Boolean = try {
        sbn.packageName != ownPackage &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            (TrackingApps.isKnown(sbn.packageName) || isPromoted(sbn.notification))
    } catch (_: RuntimeException) {
        false
    }

    fun input(sbn: StatusBarNotification, appKey: String): TrackInput? = try {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        val text = extras.charSequence(Notification.EXTRA_BIG_TEXT) ?: extras.charSequence(Notification.EXTRA_TEXT)
        val lines = extras.textLines()
        TrackInput(
            key = sbn.key,
            packageName = sbn.packageName,
            appKey = appKey,
            title = extras.charSequence(Notification.EXTRA_TITLE_BIG) ?: extras.charSequence(Notification.EXTRA_TITLE).orEmpty(),
            text = listOfNotNull(text, lines).joinToString("\n"),
            subText = extras.charSequence(Notification.EXTRA_SUB_TEXT).orEmpty(),
            postTime = sbn.postTime,
            ongoing = sbn.isOngoing,
            promoted = isPromoted(n),
            category = n.category,
            countdownTo = countdown(n, extras),
            progress = progress(n, extras),
            shortCriticalText = shortCriticalText(n),
            secret = n.visibility == Notification.VISIBILITY_SECRET,
        )
    } catch (_: RuntimeException) {
        null
    }

    fun intent(sbn: StatusBarNotification): PendingIntent? = try {
        sbn.notification.contentIntent
    } catch (_: RuntimeException) {
        null
    }

    /** The pictures of [sbn] at [px] pixels at most, or null when it has none or they can't be read. */
    fun images(sbn: StatusBarNotification, kind: TrackKind, px: Int): TrackImages? {
        imageCache[sbn.key]?.let { (time, images) -> if (time == sbn.postTime) return images }
        val images = try {
            val n = sbn.notification
            val extras = n.extras ?: Bundle.EMPTY
            // A parcel's product photo is its big picture; a driver or dish is the large icon.
            val picture = if (kind == TrackKind.PARCEL) {
                bigPicture(extras, px) ?: icon(n.getLargeIcon(), px)
            } else {
                icon(n.getLargeIcon(), px) ?: bigPicture(extras, px)
            }
            TrackImages(picture, trackerIcon(n, px / 3))
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (images != null) imageCache[sbn.key] = sbn.postTime to images
        return images
    }

    /** Forgets the pictures of notifications that are gone. */
    fun retain(keys: Set<String>) {
        imageCache.keys.retainAll(keys)
    }

    private fun isPromoted(n: Notification): Boolean =
        Build.VERSION.SDK_INT >= 36 && n.flags and Notification.FLAG_PROMOTED_ONGOING != 0

    private fun shortCriticalText(n: Notification): String? = try {
        if (Build.VERSION.SDK_INT >= 36) n.shortCriticalText else null
    } catch (_: RuntimeException) {
        null
    }

    private fun countdown(n: Notification, extras: Bundle): Long? {
        val shows = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        return n.`when`.takeIf { shows && it > 0 }
    }

    /** Android 16's ProgressStyle (segments and points) when the app used it, else the standard progress extras. */
    private fun progress(n: Notification, extras: Bundle): TrackProgress? {
        progressStyle(n)?.let { style ->
            try {
                return TrackProgress.of(
                    style.progress, style.progressMax, style.isProgressIndeterminate,
                    style.progressSegments.map { it.length }, style.progressPoints.map { it.position },
                )
            } catch (_: RuntimeException) {
                // Fall through to the plain extras.
            }
        }
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0) return null
        return TrackProgress.of(
            extras.getInt(Notification.EXTRA_PROGRESS, 0), max, extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
        )
    }

    private fun progressStyle(n: Notification): Notification.ProgressStyle? {
        if (Build.VERSION.SDK_INT < 36) return null
        // Only rebuild the style for notifications that use it: recovering a builder is not free.
        if (n.extras?.getString(Notification.EXTRA_TEMPLATE) != Notification.ProgressStyle::class.java.name) return null
        return try {
            Notification.Builder.recoverBuilder(context, n).style as? Notification.ProgressStyle
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun trackerIcon(n: Notification, px: Int): Bitmap? = try {
        progressStyle(n)?.progressTrackerIcon?.let { icon(it, px) }
    } catch (_: RuntimeException) {
        null
    }

    private fun bigPicture(extras: Bundle, px: Int): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }
        if (bitmap != null) return scaled(bitmap, px)
        val icon = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon
        }
        return icon(icon, px)
    }

    private fun icon(icon: Icon?, px: Int): Bitmap? {
        if (icon == null) return null
        // A content URI would mean a file read with the other app's grant; tiles do without.
        if (icon.type == Icon.TYPE_URI || icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP) return null
        val drawable = icon.loadDrawable(context) ?: return null
        return drawn(drawable, px)
    }

    private fun drawn(drawable: Drawable, px: Int): Bitmap? {
        (drawable as? BitmapDrawable)?.bitmap?.let { return scaled(it, px) }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: px
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: px
        val scale = minOf(1f, px.toFloat() / maxOf(w, h))
        val bw = maxOf(1, (w * scale).roundToInt())
        val bh = maxOf(1, (h * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bw, bh)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun scaled(bitmap: Bitmap, px: Int): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val scale = px.toFloat() / maxOf(bitmap.width, bitmap.height)
        // Always a copy: the tile must not hold on to a bitmap the notification owns.
        val w = maxOf(1, (bitmap.width * minOf(1f, scale)).roundToInt())
        val h = maxOf(1, (bitmap.height * minOf(1f, scale)).roundToInt())
        val config = if (bitmap.config == Bitmap.Config.HARDWARE) Bitmap.Config.ARGB_8888 else null
        val source = if (config != null) bitmap.copy(config, false) ?: return null else bitmap
        return Bitmap.createScaledBitmap(source, w, h, true).let { if (it === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    private fun Bundle.charSequence(key: String): String? = try {
        getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: RuntimeException) {
        null
    }

    private fun Bundle.textLines(): String? = try {
        getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }?.takeIf { it.isNotBlank() }
    } catch (_: RuntimeException) {
        null
    }
}
