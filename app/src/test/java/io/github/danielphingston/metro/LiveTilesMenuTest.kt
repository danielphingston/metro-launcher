package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.start.liveTileEntries
import io.github.danielphingston.metro.ui.start.offersStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTilesMenuTest {
    private val clock = AppKey("com.example.clock", "Main", 0)
    private val calendar = AppKey("com.example.calendar", "Main", 0)
    private val defaults = DefaultApps(clock = clock.packageName, calendar = calendar.packageName)

    @Test
    fun entriesListClockCalendarThenTheBuiltInTiles() {
        val entries = liveTileEntries(defaults, listOf(clock, calendar))
        assertEquals(listOf("clock", "calendar", "weather", "photos", "people", "me"), entries.map { it.label })
        assertEquals(TileContent.App(clock), entries[0].content)
        assertEquals(TileContent.App(calendar), entries[1].content)
        assertEquals(TileSize.WIDE, entries[0].size)
        assertEquals(TileSize.WIDE, entries[1].size)
        assertEquals(TileContent.Weather, entries[2].content)
        assertEquals(TileContent.Photos, entries[3].content)
        assertEquals(TileContent.People, entries[4].content)
        assertEquals(TileSize.MEDIUM, entries[4].size)
        assertEquals(TileContent.Me, entries[5].content)
        assertEquals(TileSize.WIDE, entries[5].size)
    }

    @Test
    fun clockAndCalendarDropOutWithoutADefaultApp() {
        val none = liveTileEntries(DefaultApps.EMPTY, listOf(clock, calendar))
        assertEquals(listOf("weather", "photos", "people", "me"), none.map { it.label })
        // A default that is not in the app list is left out too.
        val missing = liveTileEntries(defaults, listOf(calendar))
        assertEquals(listOf("calendar", "weather", "photos", "people", "me"), missing.map { it.label })
    }

    @Test
    fun theFirstMatchingAppWins() {
        val work = AppKey(clock.packageName, "Main", 10)
        val entries = liveTileEntries(defaults, listOf(clock, work))
        assertEquals(TileContent.App(clock), entries.first().content)
    }

    @Test
    fun styleBadgeIsHiddenForTheLiveClockAndCalendarOnly() {
        val other = AppKey("com.example.maps", "Main", 0)
        fun app(key: AppKey) = TileSpec(1, TileSize.MEDIUM, TileContent.App(key))
        assertFalse(offersStyle(app(clock), defaults))
        assertFalse(offersStyle(app(calendar), defaults))
        assertTrue(offersStyle(app(other), defaults))
        // Without defaults the same apps are ordinary tiles.
        assertTrue(offersStyle(app(clock), DefaultApps.EMPTY))
        // Tiles that were never stylable stay that way.
        assertFalse(offersStyle(TileSpec(2, TileSize.WIDE, TileContent.Weather), defaults))
    }
}
