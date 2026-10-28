package io.github.danielphingston.metro.live.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps "my location" current. The fix is only taken while a weather tile is on a STARTED
 * screen, at most once an hour, and the place only changes when the device moved far enough to
 * matter for a forecast.
 */
object WeatherLocation {
    /** A move shorter than this keeps the old place (and its cached forecast). */
    const val MIN_MOVE_KM = 5.0
    private val minInterval = TimeUnit.HOURS.toMillis(1)

    @Volatile
    private var lastCheck = 0L

    /** Great-circle distance between two coordinates, in kilometres. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }

    fun hasMoved(place: WeatherPlace, latitude: Double, longitude: Double): Boolean =
        distanceKm(place.latitude, place.longitude, latitude, longitude) > MIN_MOVE_KM

    fun isDue(now: Long, lastCheck: Long): Boolean = now - lastCheck >= minInterval

    /** Takes a new fix if the place is the device location, permission is granted and an hour has passed. */
    suspend fun refreshIfDue(context: android.content.Context, now: Long = System.currentTimeMillis()) {
        if (!DeviceLocation.hasPermission(context)) return
        if (WeatherStore.read(context).place?.isDeviceLocation != true) return
        val previous = lastCheck
        if (!isDue(now, previous)) return
        lastCheck = now
        val fix = try {
            DeviceLocation.current(context)
        } catch (e: CancellationException) {
            // The screen stopped before a fix arrived: nothing was checked, so don't wait an hour to retry.
            lastCheck = previous
            throw e
        } ?: return
        // The fix can take a while; the user may have picked another place meanwhile.
        val place = WeatherStore.read(context).place?.takeIf { it.isDeviceLocation } ?: return
        if (!hasMoved(place, fix.latitude, fix.longitude)) return
        // Storing the place restarts the tile's refresh effect and so cancels this call. The two steps
        // are quick and local (the network fetch is queued with WorkManager), and finishing both keeps
        // the stored place and its forecast refresh together.
        withContext(NonCancellable) {
            WeatherStore.setPlace(context, WeatherPlace(WeatherPlace.MY_LOCATION, "", fix.latitude, fix.longitude))
            WeatherRefresh.onSettingsChanged(context)
        }
    }
}
