package io.github.danielphingston.metro.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Recent searches (newest first) and how often and when each app was launched, keyed by [AppKey.serialize]. */
data class HistoryData(
    val searches: List<String> = emptyList(),
    val launchCounts: Map<String, Int> = emptyMap(),
    val lastLaunched: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = searches.isEmpty() && launchCounts.isEmpty() && lastLaunched.isEmpty()
}

/** The rules of the history, with no storage so they can be tested. */
object HistoryRules {
    const val MAX_SEARCHES = 8
    const val MIN_SEARCH_LENGTH = 2
    const val MAX_SEARCH_LENGTH = 80
    const val MOST_USED = 5

    /** [query] put first in [searches] (tidied, duplicates ignoring case removed); unchanged when too short to be worth keeping. */
    fun addSearch(searches: List<String>, query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ").take(MAX_SEARCH_LENGTH)
        if (q.length < MIN_SEARCH_LENGTH) return searches
        return (listOf(q) + searches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES)
    }

    /**
     * Up to [limit] of [apps], the most launched first (ties: the more recent), from the launch
     * counts; when there are no counts, the most recently launched. Apps never launched are left out.
     */
    fun mostUsed(apps: List<AppInfo>, data: HistoryData, limit: Int = MOST_USED): List<AppInfo> =
        apps.filter { (data.launchCounts[it.key.serialize()] ?: 0) > 0 || (data.lastLaunched[it.key.serialize()] ?: 0L) > 0 }
            .sortedWith(
                compareByDescending<AppInfo> { data.launchCounts[it.key.serialize()] ?: 0 }
                    .thenByDescending { data.lastLaunched[it.key.serialize()] ?: 0L }
                    .thenBy { it.label.lowercase() },
            )
            .take(limit)
}

/**
 * What the empty search shows: recent searches and launch counts, kept on this phone only. It lives
 * in its own preferences file, which backup and device transfer leave out (data_extraction_rules.xml).
 */
class SearchHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<HistoryData> = _data.asStateFlow()

    fun recordSearch(query: String) {
        val updated = HistoryRules.addSearch(_data.value.searches, query)
        if (updated != _data.value.searches) save(_data.value.copy(searches = updated))
    }

    fun recordLaunch(app: AppInfo, now: Long = System.currentTimeMillis()) {
        val key = app.key.serialize()
        val d = _data.value
        save(d.copy(launchCounts = d.launchCounts + (key to (d.launchCounts[key] ?: 0) + 1), lastLaunched = d.lastLaunched + (key to now)))
    }

    fun clear() {
        prefs.edit().clear().apply()
        _data.value = HistoryData()
    }

    private fun save(d: HistoryData) {
        _data.value = d
        prefs.edit().apply {
            clear()
            putString(SEARCHES, JSONArray(d.searches).toString())
            d.launchCounts.forEach { (k, v) -> putInt("n:$k", v) }
            d.lastLaunched.forEach { (k, v) -> putLong("t:$k", v) }
        }.apply()
    }

    private fun load(): HistoryData {
        val all = prefs.all
        val searches = runCatching {
            JSONArray(all[SEARCHES] as? String ?: "[]").let { a -> List(a.length()) { a.getString(it) } }
        }.getOrDefault(emptyList())
        return HistoryData(
            searches,
            all.filterKeys { it.startsWith("n:") }.mapKeys { it.key.drop(2) }.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap(),
            all.filterKeys { it.startsWith("t:") }.mapKeys { it.key.drop(2) }.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap(),
        )
    }

    private companion object {
        const val SEARCHES = "searches"
    }
}
