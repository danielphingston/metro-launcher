package io.github.danielphingston.metro.live.weather

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.danielphingston.metro.MetroApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the forecast fresh, with no background work at all: the weather tile asks for a refresh
 * only while Start is on screen (the screen on and the launcher showing), when the forecast is
 * older than an hour; changing the place or units refreshes at once, from settings. Earlier
 * versions refreshed hourly with WorkManager; [cancelLegacyWork] takes that schedule away once.
 */
object WeatherRefresh {
    private const val LEGACY_PERIODIC = "weather-periodic"
    private const val LEGACY_ONCE = "weather-once"
    private val maxAge = TimeUnit.MINUTES.toMillis(60)

    /** After a failed download, the wait before the next try. */
    internal val RETRY_MS = TimeUnit.MINUTES.toMillis(5)

    private val lock = Mutex()

    @Volatile
    private var failedAt = 0L

    fun isStale(stored: StoredWeather, now: Long = System.currentTimeMillis()): Boolean =
        stored.forecastJson == null || now - stored.fetchedAt > maxAge

    /**
     * When the tile should next look: an hour after the forecast was fetched (or [RETRY_MS] after
     * a failure), rounded up to a whole minute so it can share a wake with other tiles; never
     * before [now] + 1 s.
     */
    fun nextCheck(stored: StoredWeather?, lastFailure: Long, now: Long): Long {
        val due = when {
            lastFailure > (stored?.fetchedAt ?: 0L) -> lastFailure + RETRY_MS
            stored?.forecastJson == null -> now
            else -> stored.fetchedAt + maxAge
        }
        val minute = TimeUnit.MINUTES.toMillis(1)
        return maxOf((due + minute - 1) / minute * minute, now + 1000)
    }

    /** When the last download failed (0: none since the app started). */
    val lastFailure: Long get() = failedAt

    /**
     * Downloads the forecast now, in the app's own process, if it is stale or [force]; a download
     * already running is not repeated. Call only while the user is looking: Start shown, or
     * settings open. A failure waits [RETRY_MS] before the next unforced try.
     */
    suspend fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!lock.tryLock()) return
        try {
            val stored = WeatherStore.read(app)
            val place = stored.place ?: return
            val now = System.currentTimeMillis()
            if (!force && (!isStale(stored, now) || now - failedAt < RETRY_MS)) return
            try {
                val json = withContext(Dispatchers.IO) { WeatherApi.forecast(place, stored.fahrenheit) }
                WeatherParser.forecast(json) // Reject a malformed response instead of caching it.
                WeatherStore.saveForecast(app, place, stored.fahrenheit, json, System.currentTimeMillis())
                failedAt = 0
            } catch (e: IOException) {
                failedAt = System.currentTimeMillis()
            } catch (e: JSONException) {
                failedAt = System.currentTimeMillis()
            }
        } finally {
            lock.unlock()
        }
    }

    /** The location or units changed (in settings, or a restored backup): refresh at once, while the user is there. */
    fun onSettingsChanged(context: Context) {
        val app = context.applicationContext as MetroApplication
        app.appScope.launch { refresh(app, force = true) }
    }

    /**
     * Removes the hourly background refresh earlier versions scheduled; once per install (a flag
     * in no-backup preferences), so WorkManager isn't started on every launch after that.
     */
    fun cancelLegacyWork(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("weather_refresh", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_cancelled", false)) return
        runCatching {
            val work = WorkManager.getInstance(context.applicationContext)
            work.cancelUniqueWork(LEGACY_PERIODIC)
            work.cancelUniqueWork(LEGACY_ONCE)
        }
        prefs.edit().putBoolean("legacy_cancelled", true).apply()
    }
}

/**
 * Kept only so a refresh earlier versions scheduled, firing before [WeatherRefresh.cancelLegacyWork]
 * has run, finds its class: it cancels the schedule and downloads nothing.
 */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WeatherRefresh.cancelLegacyWork(applicationContext)
        return Result.success()
    }
}
