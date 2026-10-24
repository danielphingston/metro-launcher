package io.github.danielphingston.metro

import io.github.danielphingston.metro.live.PhotoSource
import io.github.danielphingston.metro.live.weather.WeatherApi
import io.github.danielphingston.metro.live.weather.WeatherLocation
import io.github.danielphingston.metro.live.weather.WeatherPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

class WeatherLocationTest {
    private val london = WeatherPlace(WeatherPlace.MY_LOCATION, "", 51.5074, -0.1278)

    @Test
    fun distanceBetweenKnownPoints() {
        // London to Paris is about 344 km.
        val km = WeatherLocation.distanceKm(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(344.0, km, 3.0)
        assertEquals(0.0, WeatherLocation.distanceKm(10.0, 20.0, 10.0, 20.0), 1e-9)
    }

    @Test
    fun smallMovesKeepThePlace() {
        // About 1 km north.
        assertFalse(WeatherLocation.hasMoved(london, 51.5164, -0.1278))
    }

    @Test
    fun movesPastTheThresholdUpdateThePlace() {
        // About 11 km north.
        assertTrue(WeatherLocation.hasMoved(london, 51.6074, -0.1278))
    }

    @Test
    fun fixesAreAtMostHourly() {
        val hour = TimeUnit.HOURS.toMillis(1)
        assertTrue(WeatherLocation.isDue(now = hour, lastCheck = 0))
        assertFalse(WeatherLocation.isDue(now = hour - 1, lastCheck = 0))
        assertFalse(WeatherLocation.isDue(now = 1_000 + 30 * 60_000, lastCheck = 1_000))
    }
}

class WeatherApiTest {
    @Test
    fun forecastRequestRoundsCoordinatesToTwoDecimals() {
        val url = WeatherApi.forecastUrl(WeatherPlace("x", "", 51.50739, -0.12786), fahrenheit = false)
        assertTrue(url, "latitude=51.51&" in url)
        assertTrue(url, "longitude=-0.13&" in url)
        assertFalse("temperature_unit" in url)
    }

    @Test
    fun forecastRequestAsksForFahrenheit() {
        val url = WeatherApi.forecastUrl(WeatherPlace("x", "", 1.0, 2.0), fahrenheit = true)
        assertTrue("temperature_unit=fahrenheit" in url)
    }
}

class PhotoPersistenceTest {
    @Test
    fun onlyUrisWithAPersistedGrantAreKept() {
        val kept = PhotoSource.keepPersisted(listOf("a", "b", "c")) { it != "b" }
        assertEquals(listOf("a", "c"), kept)
    }

    @Test
    fun noUriIsKeptWhenNoneCouldBePersisted() {
        assertEquals(emptyList<String>(), PhotoSource.keepPersisted(listOf("a", "b")) { false })
    }

    @Test
    fun onlyLostAccessDropsAPhoto() {
        assertTrue(PhotoSource.isAccessLost(SecurityException("revoked")))
        assertFalse(PhotoSource.isAccessLost(FileNotFoundException("gone for now")))
        assertFalse(PhotoSource.isAccessLost(IOException("flaky")))
        assertFalse(PhotoSource.isAccessLost(IllegalStateException("corrupt")))
    }
}
