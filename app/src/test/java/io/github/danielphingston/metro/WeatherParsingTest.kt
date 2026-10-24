package io.github.danielphingston.metro

import io.github.danielphingston.metro.live.weather.WeatherCondition
import io.github.danielphingston.metro.live.weather.WeatherParser
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

// org.json is an Android API; Robolectric provides the real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WeatherParsingTest {
    private val forecast = """
        {
          "latitude": 51.5, "longitude": -0.12, "timezone": "Europe/London",
          "current": {"time": "2026-10-24T14:00", "temperature_2m": 12.6, "weather_code": 2, "is_day": 1},
          "daily": {
            "time": ["2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27"],
            "weather_code": [2, 61, 3, 95],
            "temperature_2m_max": [14.4, 11.5, -0.5, 9.0],
            "temperature_2m_min": [7.2, 6.0, -3.6, 4.9]
          }
        }
    """.trimIndent()

    @Test
    fun parsesCurrentConditions() {
        val parsed = WeatherParser.forecast(forecast)
        assertEquals(13, parsed.temperature)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, parsed.condition)
        assertTrue(parsed.isDay)
    }

    @Test
    fun parsesNightFromIsDay() {
        val night = forecast.replace("\"is_day\": 1", "\"is_day\": 0")
        assertFalse(WeatherParser.forecast(night).isDay)
    }

    @Test
    fun parsesDailyForecastWithRoundedTemperatures() {
        val days = WeatherParser.forecast(forecast).days
        assertEquals(4, days.size)
        assertEquals(LocalDate.of(2026, 10, 24), days[0].date)
        assertEquals(14, days[0].high)
        assertEquals(7, days[0].low)
        assertEquals(WeatherCondition.RAIN, days[1].condition)
        assertEquals(WeatherCondition.THUNDERSTORM, days[3].condition)
        assertEquals(-4, days[2].low)
    }

    @Test(expected = JSONException::class)
    fun rejectsResponseWithoutCurrent() {
        WeatherParser.forecast("""{"error": true, "reason": "Latitude must be in range of -90 to 90"}""")
    }

    @Test
    fun parsesGeocodingResults() {
        val places = WeatherParser.places(
            """
            {"results": [
              {"id": 1, "name": "London", "latitude": 51.50853, "longitude": -0.12574,
               "country": "United Kingdom", "admin1": "England"},
              {"id": 2, "name": "London", "latitude": 42.98339, "longitude": -81.23304,
               "country": "Canada", "admin1": "Ontario"},
              {"id": 3, "name": "Londonderry", "latitude": 54.99721, "longitude": -7.30917}
            ], "generationtime_ms": 0.9}
            """.trimIndent(),
        )
        assertEquals(3, places.size)
        assertEquals("London", places[0].name)
        assertEquals("England, United Kingdom", places[0].detail)
        assertEquals(-81.23304, places[1].longitude, 1e-9)
        assertEquals("", places[2].detail)
    }

    @Test
    fun noGeocodingMatchesIsEmpty() {
        // The API omits "results" entirely when nothing matches.
        assertTrue(WeatherParser.places("""{"generationtime_ms": 0.4}""").isEmpty())
    }

    @Test
    fun mapsWmoCodes() {
        val expected = mapOf(
            0 to WeatherCondition.CLEAR, 1 to WeatherCondition.CLEAR,
            2 to WeatherCondition.PARTLY_CLOUDY, 3 to WeatherCondition.CLOUDY,
            45 to WeatherCondition.FOG, 48 to WeatherCondition.FOG,
            51 to WeatherCondition.DRIZZLE, 55 to WeatherCondition.DRIZZLE, 56 to WeatherCondition.DRIZZLE,
            61 to WeatherCondition.RAIN, 65 to WeatherCondition.RAIN, 67 to WeatherCondition.RAIN,
            71 to WeatherCondition.SNOW, 75 to WeatherCondition.SNOW, 77 to WeatherCondition.SNOW,
            80 to WeatherCondition.SHOWERS, 82 to WeatherCondition.SHOWERS,
            85 to WeatherCondition.SNOW, 86 to WeatherCondition.SNOW,
            95 to WeatherCondition.THUNDERSTORM, 96 to WeatherCondition.THUNDERSTORM, 99 to WeatherCondition.THUNDERSTORM,
        )
        expected.forEach { (code, condition) -> assertEquals("code $code", condition, WeatherCondition.fromWmo(code)) }
    }

    @Test
    fun unknownWmoCodeFallsBackToCloudy() {
        assertEquals(WeatherCondition.CLOUDY, WeatherCondition.fromWmo(1234))
    }
}
