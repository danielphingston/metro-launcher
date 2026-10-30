package io.github.danielphingston.metro.data

import android.content.Context
import io.github.danielphingston.metro.live.NotificationPreview
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.concurrent.atomic.AtomicInteger
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
        return SavedReply(packageName, text.title, truncate(text.reply), preview.postTime)
    }

    /** Cuts [text] to [MAX_REPLY] chars without leaving half of a surrogate pair (an emoji) at the end. */
    internal fun truncate(text: String): String {
        if (text.length <= MAX_REPLY) return text
        val end = if (Character.isHighSurrogate(text[MAX_REPLY - 1])) MAX_REPLY - 1 else MAX_REPLY
        return text.substring(0, end)
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
class AssistantReplyStore(
    context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(context.noBackupFilesDir, FILE)
    private val lock = Mutex()
    private val _reply = MutableStateFlow<SavedReply?>(null)
    val reply: StateFlow<SavedReply?> = _reply.asStateFlow()

    /**
     * Bumped by every save and clear. Disk work queued earlier (the first load, an older save) only
     * acts while it is still the newest operation, so it can never undo a later clear or save.
     */
    private val generation = AtomicInteger()

    init {
        val mine = generation.get()
        scope.launch(io) {
            lock.withLock {
                // A save or clear that came after this load was queued is newer than what is on disk.
                if (generation.get() == mine && _reply.value == null) {
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
        val mine = generation.incrementAndGet()
        scope.launch(io) {
            lock.withLock {
                if (generation.get() != mine) return@withLock // Superseded; the newer operation owns the file.
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
        val mine = generation.incrementAndGet()
        scope.launch(io) {
            lock.withLock {
                if (generation.get() != mine) return@withLock
                file.delete(); File(file.parentFile, "$FILE.tmp").delete()
            }
        }
    }

    private companion object {
        const val FILE = "assistant_last_reply.json"
    }
}

/**
 * The last question asked from search, for the assistant tile's back. Like the reply it is the
 * user's conversation, so it lives in [Context.getNoBackupFilesDir] and not in the backed-up
 * settings. A question the settings store still holds from an older version is moved here once
 * ([legacy] reads it and deletes it there).
 */
class LastAskedStore(
    context: Context,
    private val scope: CoroutineScope,
    private val legacy: suspend () -> LastAsked? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(context.noBackupFilesDir, FILE)
    private val lock = Mutex()
    private val generation = AtomicInteger()
    private val _asked = MutableStateFlow<LastAsked?>(null)
    val asked: StateFlow<LastAsked?> = _asked.asStateFlow()

    init {
        val mine = generation.get()
        scope.launch(io) {
            lock.withLock {
                val stored = try { if (file.exists()) fromJson(file.readText()) else null } catch (_: IOException) { null }
                val loaded = stored ?: legacy()?.also { write(it) }
                // A set or clear after this load was queued is newer than what was on disk.
                if (generation.get() == mine) _asked.value = loaded
            }
        }
    }

    fun set(question: String, at: Long) {
        val value = LastAsked(AssistantIntents.storable(question), at)
        _asked.value = value
        val mine = generation.incrementAndGet()
        scope.launch(io) { lock.withLock { if (generation.get() == mine) write(value) } }
    }

    fun clear() {
        _asked.value = null
        val mine = generation.incrementAndGet()
        scope.launch(io) { lock.withLock { if (generation.get() == mine) file.delete() } }
    }

    private fun write(value: LastAsked) {
        try {
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(JSONObject().put("text", value.text).put("at", value.at).toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: IOException) {
            // The tile still has it in memory.
        }
    }

    private fun fromJson(text: String): LastAsked? = try {
        JSONObject(text).let { LastAsked(it.getString("text"), it.getLong("at")) }.takeIf { it.text.isNotBlank() }
    } catch (_: JSONException) {
        null
    }

    private companion object {
        const val FILE = "assistant_last_asked.json"
    }
}
