package io.github.danielphingston.metro.live.tracking

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * A parcel caught "arriving today" or "out for delivery", kept after its notification is gone until
 * it is delivered or [until] (the next local midnight). [title] and [detail] are blank when the
 * notification's content was not allowed to show.
 */
data class ParcelEntry(
    val key: String,
    val packageName: String,
    val appKey: String,
    val stage: ParcelStage,
    val title: String,
    val detail: String,
    val caughtAt: Long,
    val until: Long,
)

/** Pure rules for the kept parcels; [ParcelStore] only reads and writes what these return. */
object ParcelLog {
    const val MAX_ENTRIES = 20

    /** What is left of [entries] at [now]: only those before their midnight. */
    fun live(entries: List<ParcelEntry>, now: Long): List<ParcelEntry> = entries.filter { now < it.until }

    /**
     * [entries] after this rebuild saw [parcels] (parcel items only, any stage). A delivered one
     * removes what it delivers; the others are added or moved on (never back: out for delivery
     * stays out for delivery). [keepText] false keeps only the app and the stage.
     */
    fun update(
        entries: List<ParcelEntry>,
        parcels: List<TrackItem>,
        now: Long,
        keepText: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ParcelEntry> {
        var result = live(entries, now)
        val todayStart = nextMidnight(now, zone).let { Instant.ofEpochMilli(it).atZone(zone).minusDays(1).toInstant().toEpochMilli() }
        for (item in parcels.sortedBy { it.postTime }) {
            val stage = item.stage ?: continue
            if (stage == ParcelStage.DELIVERED) {
                result = removeDelivered(result, item)
                continue
            }
            // Yesterday's "arriving today", still in the shade, is not about today.
            if (item.postTime < todayStart) continue
            val text = keepText && !item.secret
            val existing = result.firstOrNull { it.key == item.key && it.packageName == item.packageName }
            val entry = ParcelEntry(
                key = item.key,
                packageName = item.packageName,
                appKey = item.appKey,
                stage = if (existing != null && existing.stage.ordinal > stage.ordinal) existing.stage else stage,
                title = if (text) item.status else "",
                detail = if (text) item.detail else "",
                caughtAt = existing?.caughtAt ?: now,
                until = nextMidnight(now, zone),
            )
            result = if (existing != null) result.map { if (it === existing) entry else it } else result + entry
        }
        return result.sortedByDescending { it.caughtAt }.take(MAX_ENTRIES)
    }

    /**
     * Drops what a "delivered" notification is about: the entry it updates (same notification),
     * else one from the same app that shares its words, else the app's only entry.
     */
    internal fun removeDelivered(entries: List<ParcelEntry>, delivered: TrackItem): List<ParcelEntry> {
        val same = entries.filter { it.key == delivered.key && it.packageName == delivered.packageName }
        if (same.isNotEmpty()) return entries - same.toSet()
        val fromApp = entries.filter { it.packageName == delivered.packageName }
        if (fromApp.isEmpty()) return entries
        val words = wordsOf("${delivered.status} ${delivered.detail}")
        val match = fromApp.maxByOrNull { (wordsOf("${it.title} ${it.detail}") intersect words).size }
        val shared = match?.let { (wordsOf("${it.title} ${it.detail}") intersect words).size } ?: 0
        return when {
            shared >= 2 -> entries - match!!
            fromApp.size == 1 -> entries - fromApp.first()
            else -> entries
        }
    }

    /** Words that can tell parcels apart: not the ones every parcel notification uses. */
    private fun wordsOf(text: String): Set<String> = text.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in COMMON }
        .toSet()

    private val COMMON = setOf(
        "your", "the", "and", "for", "has", "been", "was", "delivered", "delivery", "out", "today", "arriving",
        "order", "package", "parcel", "shipment", "item", "items", "will", "arrive", "by", "with", "from",
    )

    /** The start of the day after [now], in [zone]. */
    fun nextMidnight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun encode(entries: List<ParcelEntry>): String = JSONArray().apply {
        entries.forEach {
            put(
                JSONObject()
                    .put("key", it.key).put("package", it.packageName).put("appKey", it.appKey)
                    .put("stage", it.stage.id).put("title", it.title).put("detail", it.detail)
                    .put("caught", it.caughtAt).put("until", it.until),
            )
        }
    }.toString()

    /** Whatever of [text] can be read; anything damaged or unknown is skipped, never thrown. */
    fun decode(text: String?): List<ParcelEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                ParcelEntry(
                    key = o.getString("key"),
                    packageName = o.getString("package").takeIf { it.isNotBlank() } ?: return@runCatching null,
                    appKey = o.getString("appKey"),
                    stage = ParcelStage.fromId(o.optString("stage"))?.takeIf { it != ParcelStage.DELIVERED } ?: return@runCatching null,
                    title = o.optString("title").take(300),
                    detail = o.optString("detail").take(300),
                    caughtAt = o.getLong("caught"),
                    until = o.getLong("until"),
                )
            }.getOrNull()
        }
    }
}

/**
 * The kept parcels, in SharedPreferences "tracking" (left out of cloud backup and device transfer,
 * and not part of the settings export). Written only from the notification listener's rebuild,
 * and only when something changed.
 */
class ParcelStore(
    context: Context,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow<List<ParcelEntry>>(emptyList())
    val entries: StateFlow<List<ParcelEntry>> = _entries.asStateFlow()

    @Volatile private var loaded = false

    init {
        scope.launch(io) { load() }
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _entries.value = ParcelLog.live(ParcelLog.decode(runCatching { prefs.getString(KEY, null) }.getOrNull()), System.currentTimeMillis())
            loaded = true
        }
    }

    /** Called from the listener's rebuild (a background thread). */
    fun update(parcels: List<TrackItem>, now: Long, keepText: Boolean) {
        load()
        synchronized(this) {
            val current = _entries.value
            val next = ParcelLog.update(current, parcels, now, keepText)
            if (next == current) return
            _entries.value = next
            runCatching { prefs.edit().putString(KEY, ParcelLog.encode(next)).apply() }
        }
    }

    /** Notification access was taken away: nothing caught through it is kept. */
    fun clear() {
        synchronized(this) {
            _entries.value = emptyList()
            loaded = true
            runCatching { prefs.edit().remove(KEY).apply() }
        }
    }

    /** Notification content was turned off: keep the parcels but forget their words. */
    fun forgetText() {
        load()
        synchronized(this) {
            val current = _entries.value
            if (current.none { it.title.isNotEmpty() || it.detail.isNotEmpty() }) return
            val next = current.map { it.copy(title = "", detail = "") }
            _entries.value = next
            runCatching { prefs.edit().putString(KEY, ParcelLog.encode(next)).apply() }
        }
    }

    private companion object {
        const val FILE = "tracking"
        const val KEY = "parcels"
    }
}
