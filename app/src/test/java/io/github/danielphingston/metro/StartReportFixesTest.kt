package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.StartPadding
import io.github.danielphingston.metro.live.ClockSource
import io.github.danielphingston.metro.live.weather.WeatherPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class StartReportFixesTest {
    @Test
    fun onlyClockAppAlarmsCount() {
        val clocks = setOf("com.google.android.deskclock")
        assertEquals(true, ClockSource.isFromClockApp("com.google.android.deskclock", clocks))
        assertEquals(false, ClockSource.isFromClockApp("com.example.reminders", clocks))
        assertEquals(false, ClockSource.isFromClockApp(null, clocks))
        // No clock app visible: keep what the system reports rather than hide every alarm.
        assertEquals(true, ClockSource.isFromClockApp("com.example.reminders", emptySet()))
    }

    @Test
    fun deviceLocationShowsItsTown() {
        assertEquals("Aroor", WeatherPlace(WeatherPlace.MY_LOCATION, "Aroor", 9.8, 76.3).shownName)
        assertEquals(WeatherPlace.MY_LOCATION, WeatherPlace(WeatherPlace.MY_LOCATION, "", 9.8, 76.3).shownName)
        assertEquals("Kochi", WeatherPlace("Kochi", "Kerala, India", 9.9, 76.2).shownName)
    }

    @Test
    fun negativeTopPaddingIsKept() {
        val padding = StartPadding.fit(LauncherSettings(startTopPadding = -40), 400f, 800f, 8f, 4)
        assertEquals(-40f, padding.top)
        assertEquals(LauncherSettings.MIN_START_TOP_PADDING, LauncherSettings.clampStartTopPadding(-500))
    }
}
