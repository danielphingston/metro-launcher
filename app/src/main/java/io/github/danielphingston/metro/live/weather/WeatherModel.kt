package io.github.danielphingston.metro.live.weather

import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/** What the sky is doing, from Open-Meteo's WMO weather codes. */
enum class WeatherCondition(val label: String) {
    CLEAR("clear"),
    PARTLY_CLOUDY("partly cloudy"),
    CLOUDY("cloudy"),
    FOG("fog"),
    DRIZZLE("drizzle"),
    RAIN("rain"),
    SNOW("snow"),
    SHOWERS("showers"),
    THUNDERSTORM("thunderstorm");

    companion object {
        /** Maps a WMO code (https://open-meteo.com/en/docs) to the nearest condition. */
        fun fromWmo(code: Int): WeatherCondition = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 80..82 -> SHOWERS
            in 95..99 -> THUNDERSTORM
            else -> CLOUDY
        }
    }
}

/** A place the user can pick, from the geocoding search or the device location. */
data class WeatherPlace(val name: String, val detail: String, val latitude: Double, val longitude: Double) {
    /** The device location has no name; the tile shows [MY_LOCATION]. */
    val isDeviceLocation: Boolean get() = name == MY_LOCATION

    companion object {
        const val MY_LOCATION = "my location"
    }
}

/** One day of the daily forecast; [date] is the place's local date. */
data class DayForecast(val date: LocalDate, val condition: WeatherCondition, val high: Int, val low: Int)

/** Current conditions plus [days], starting with today. Temperatures are in the unit that was requested. */
data class Forecast(
    val temperature: Int,
    val condition: WeatherCondition,
    val isDay: Boolean,
    val days: List<DayForecast>,
) {
    val today: DayForecast? get() = days.firstOrNull()
}

/** Parsers for Open-Meteo responses. They throw [org.json.JSONException] on malformed input. */
object WeatherParser {
    fun forecast(json: String): Forecast {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val days = (0 until dates.length()).map { i ->
            DayForecast(
                LocalDate.parse(dates.getString(i)),
                WeatherCondition.fromWmo(codes.getInt(i)),
                highs.getDouble(i).roundToInt(),
                lows.getDouble(i).roundToInt(),
            )
        }
        return Forecast(
            temperature = current.getDouble("temperature_2m").roundToInt(),
            condition = WeatherCondition.fromWmo(current.getInt("weather_code")),
            isDay = current.optInt("is_day", 1) == 1,
            days = days,
        )
    }

    /** Geocoding results; an empty list when nothing matched (the API then omits "results"). */
    fun places(json: String): List<WeatherPlace> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }.map { place ->
            WeatherPlace(
                name = place.getString("name"),
                detail = listOf("admin1", "country").mapNotNull { place.optString(it).takeIf(String::isNotBlank) }
                    .joinToString(", "),
                latitude = place.getDouble("latitude"),
                longitude = place.getDouble("longitude"),
            )
        }
    }
}
