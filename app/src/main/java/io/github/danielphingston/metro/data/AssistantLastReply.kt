package io.github.danielphingston.metro.data

import android.content.Context
import io.github.danielphingston.metro.live.NotificationPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The last assistant reply Metro saw, kept so the tile still has something to show once the notification is gone. */
data class SavedReply(
    val packageName: String,
    /** The conversation title, or null when the notification had none worth showing. */
    val title: String?,
    val reply: String,
    val postTime: Long,
) {
    /** What was shown is what matters; a re-post of the same text at a new time is not a change. */
    fun sameContent(other: SavedReply?): Boolean =
        other != null && packageName == other.packageName && title == other.title && reply == other.reply
}

/** Pure decisions around the saved reply, free of Android for unit tests. */
object AssistantLastReply {
    /** Replies are cut here: the tile never shows more, and the file stays tiny. */
    const val MAX_REPLY = 2000

    /**
     * The reply to save for [preview] from [packageName], or null if there is nothing to keep.
     * [preview] must already have passed the SECRET rule, and [showContent] is the user's
     * notification-content setting: content that would not be shown is never stored.
     */
    fun toSave(packageName: String, appLabel: String, preview: NotificationPreview?, showContent: Boolean): SavedReply? {
        if (!showContent || preview == null) return null
        val text = AssistantText.extract(appLabel, preview.title, preview.text)
        if (text.reply.isBlank() && text.title.isNullOrBlank()) return null
        return SavedReply(packageName, text.title, text.reply.take(MAX_REPLY), preview.postTime)
    }

    /** Write only when there is something new to keep. */
    fun shouldWrite(current: SavedReply?, candidate: SavedReply?): Boolean =
        candidate != null && !candidate.sameContent(current)

    /**
     * A question belongs with a saved reply only if it was asked before the reply arrived; an older
     * reply with a newer question would pair unrelated things.
     */
    fun pairsWithAsked(replyTime: Long, askedAt: Long): Boolean = replyTime > askedAt

    enum class Source { LIVE, SAVED, NONE }

    /**
     * Which content the tile shows: the live notification when it has content, else the saved
     * reply, but only if it belongs to the current assistant and content is allowed to show.
     */
    fun source(liveHasReply: Boolean, saved: SavedReply?, currentPackage: String?, showContent: Boolean): Source = when {
        liveHasReply -> Source.LIVE
        showContent && saved != null && currentPackage != null && saved.packageName == currentPackage -> Source.SAVED
        else -> Source.NONE
    }

    /**
     * "just now", "5m ago", "2h ago", "yesterday", "3 days ago", then a short date. [zone] decides
     * where "yesterday" starts. A time in the future (clock changes) counts as "just now".
     */
    fun relativeTime(now: Long, then: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val seconds = (now - then) / 1000
        if (seconds < 60) return "just now"
        if (seconds < 3600) return "${seconds / 60}m ago"
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(then).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        )
        return when {
            days <= 0 -> "${seconds / 3600}h ago"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            else -> DateTimeFormatter.ofPattern("MMM d", locale).withZone(zone).format(Instant.ofEpochMilli(then))
        }
    }

    internal fun toJson(r: SavedReply): String = JSONObject()
        .put("package", r.packageName)
        .put("title", r.title ?: JSONObject.NULL)
        .put("reply", r.reply)
        .put("time", r.postTime)
        .toString()

    internal fun fromJson(text: String): SavedReply? = try {
        val o = JSONObject(text)
        SavedReply(
            o.getString("package"),
            if (o.isNull("title")) null else o.getString("title"),
            o.getString("reply"),
            o.getLong("time"),
        )
    } catch (_: JSONException) {
        null
    }
}

/**
 * The saved reply on disk. It lives in [Context.getNoBackupFilesDir], which is never part of cloud
 * backup or device transfer (conversation text is private and tied to this phone's notifications).
 * Reads and writes happen on the IO dispatcher, one at a time in call order; [reply] is updated
 * at once so the tile does not wait for the disk.
 */
class AssistantReplyStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.noBackupFilesDir, FILE)
    private val lock = Mutex()
    private val _reply = MutableStateFlow<SavedReply?>(null)
    val reply: StateFlow<SavedReply?> = _reply.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                // A save that raced ahead of this load is newer than what is on disk.
                if (_reply.value == null) {
                    _reply.value = try { if (file.exists()) AssistantLastReply.fromJson(file.readText()) else null } catch (_: IOException) { null }
                }
            }
        }
    }

    /** Saves [candidate] if it differs from what is kept. */
    fun save(candidate: SavedReply?) {
        if (!AssistantLastReply.shouldWrite(_reply.value, candidate)) return
        candidate!!
        _reply.value = candidate
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                try {
                    val tmp = File(file.parentFile, "$FILE.tmp")
                    tmp.writeText(AssistantLastReply.toJson(candidate))
                    if (!tmp.renameTo(file)) tmp.delete()
                } catch (_: IOException) {
                    // Keeping the reply is a convenience; the tile still has it in memory.
                }
            }
        }
    }

    fun clear() {
        _reply.value = null
        scope.launch(Dispatchers.IO) {
            lock.withLock { file.delete(); File(file.parentFile, "$FILE.tmp").delete() }
        }
    }

    private companion object {
        const val FILE = "assistant_last_reply.json"
    }
}
