package io.github.danielphingston.metro.live.weather

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.TileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the forecast fresh with WorkManager, the only background work the weather tile does:
 * about hourly while a weather tile is pinned, only with a network and not on a low battery.
 */
object WeatherRefresh {
    private const val PERIODIC = "weather-periodic"
    private const val ONCE = "weather-once"
    private val maxAge = TimeUnit.MINUTES.toMillis(60)

    fun isStale(stored: StoredWeather, now: Long = System.currentTimeMillis()): Boolean =
        stored.forecastJson == null || now - stored.fetchedAt > maxAge

    /** Schedules the hourly refresh unless it already is. Call only with a location set and a tile pinned. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WeatherWorker>(60, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Downloads once as soon as there is a network. With [replace] (the user just changed the
     * location or units) it supersedes a pending download; otherwise a pending one is kept.
     */
    fun refreshNow(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<WeatherWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONCE, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** The location or units changed: refresh now, and keep refreshing if a weather tile is pinned. */
    fun onSettingsChanged(context: Context) {
        val pinned = (context.applicationContext as MetroApplication).tiles.isPinned(TileContent.Weather)
        if (pinned) schedulePeriodic(context)
        refreshNow(context, replace = true)
    }
}

class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tiles = (applicationContext as MetroApplication).tiles
        // The tile list loads asynchronously after a cold start.
        withTimeoutOrNull(10_000) { tiles.tiles.filterNotNull().first() } ?: return Result.retry()
        if (!tiles.isPinned(TileContent.Weather)) {
            WeatherRefresh.cancel(applicationContext)
            return Result.success()
        }
        val stored = WeatherStore.read(applicationContext)
        val place = stored.place ?: return Result.success()
        return try {
            val json = withContext(Dispatchers.IO) { WeatherApi.forecast(place, stored.fahrenheit) }
            WeatherParser.forecast(json) // Reject a malformed response instead of caching it.
            WeatherStore.saveForecast(applicationContext, place, stored.fahrenheit, json, System.currentTimeMillis())
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: JSONException) {
            Result.failure()
        }
    }
}
