package io.github.danielphingston.metro.live.weather

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.metroDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** The user's weather settings and the last downloaded forecast ([forecastJson] is the raw response). */
data class StoredWeather(
    val place: WeatherPlace? = null,
    val fahrenheit: Boolean = false,
    val forecastJson: String? = null,
    /** When [forecastJson] was downloaded, epoch millis. */
    val fetchedAt: Long = 0,
)

/** Weather state in the shared DataStore, under its own keys. */
object WeatherStore {
    private val name = stringPreferencesKey("weather_name")
    private val detail = stringPreferencesKey("weather_detail")
    private val latitude = doublePreferencesKey("weather_latitude")
    private val longitude = doublePreferencesKey("weather_longitude")
    private val fahrenheit = booleanPreferencesKey("weather_fahrenheit")
    private val forecast = stringPreferencesKey("weather_forecast")
    private val fetchedAt = longPreferencesKey("weather_fetched_at")

    /**
     * The last value read or written, for callers that can't wait for DataStore (e.g. choosing
     * what a tap opens on the main thread). Null until the first read.
     */
    @Volatile
    var latest: StoredWeather? = null
        private set

    /** Emits only when a weather value changes, not for unrelated settings in the same file. */
    fun stored(context: Context): Flow<StoredWeather> =
        context.metroDataStore.data.map { it.toStored() }.onEach { latest = it }.distinctUntilChanged()

    suspend fun read(context: Context): StoredWeather = stored(context).first()

    private fun Preferences.toStored(): StoredWeather {
        val lat = this[latitude]
        val lon = this[longitude]
        val place = if (lat != null && lon != null) {
            WeatherPlace(this[name].orEmpty(), this[detail].orEmpty(), lat, lon)
        } else {
            null
        }
        return StoredWeather(place, this[fahrenheit] == true, this[forecast], this[fetchedAt] ?: 0)
    }

    /** Names [place] [detail] without touching its forecast, if it is still the stored place. */
    suspend fun setPlaceName(context: Context, place: WeatherPlace, detail: String) {
        context.metroDataStore.edit {
            if (it[latitude] != place.latitude || it[longitude] != place.longitude || it[name] != place.name) return@edit
            it[this.detail] = detail
        }
    }

    /** Sets the location and drops the forecast, which belongs to the old one. */
    suspend fun setPlace(context: Context, place: WeatherPlace) {
        latest = latest?.copy(place = place, forecastJson = null, fetchedAt = 0)
        context.metroDataStore.edit {
            it[name] = place.name
            it[detail] = place.detail
            it[latitude] = place.latitude
            it[longitude] = place.longitude
            it.clearForecast()
        }
    }

    /** Switches units and drops the forecast, which was downloaded in the old ones. */
    suspend fun setFahrenheit(context: Context, enabled: Boolean) {
        context.metroDataStore.edit {
            it[fahrenheit] = enabled
            it.clearForecast()
        }
    }

    /**
     * Saves a download made for [place] and [inFahrenheit], unless the user changed either
     * while it was in flight.
     */
    suspend fun saveForecast(context: Context, place: WeatherPlace, inFahrenheit: Boolean, json: String, now: Long) {
        context.metroDataStore.edit {
            val current = it.toStored()
            val samePlace = current.place?.let { p -> p.latitude == place.latitude && p.longitude == place.longitude }
            if (samePlace == true && current.fahrenheit == inFahrenheit) {
                it[forecast] = json
                it[fetchedAt] = now
            }
        }
    }

    private fun MutablePreferences.clearForecast() {
        remove(forecast)
        remove(fetchedAt)
    }
}
