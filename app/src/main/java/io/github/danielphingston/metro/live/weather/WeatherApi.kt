package io.github.danielphingston.metro.live.weather

import io.github.danielphingston.metro.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Open-Meteo requests (free, no key). Blocking: call from a background dispatcher. */
object WeatherApi {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

    /** The raw forecast response for [place], in °F when [fahrenheit]. */
    fun forecast(place: WeatherPlace, fahrenheit: Boolean): String {
        val units = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        return get(
            String.format(
                Locale.US,
                "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4%s",
                FORECAST, place.latitude, place.longitude, units,
            ),
        )
    }

    fun search(name: String): List<WeatherPlace> =
        WeatherParser.places(get("$GEOCODING?name=${URLEncoder.encode(name, "UTF-8")}&count=5"))

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "MetroLauncher/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
