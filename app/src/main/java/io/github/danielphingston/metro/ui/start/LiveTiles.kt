package io.github.danielphingston.metro.ui.start

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStyles

/** One line of the "live tiles" add menu: what it pins and at which size. */
data class LiveTileEntry(val label: String, val content: TileContent, val size: TileSize)

/**
 * The entries of the "live tiles" menu. Clock and calendar pin the phone's default app, which then
 * draws its live face by itself; they are left out when there is no such app. [appKeys] are the
 * launchable apps, the personal profile's first, so a work-profile twin is only a fallback.
 */
internal fun liveTileEntries(defaults: DefaultApps, appKeys: List<AppKey>): List<LiveTileEntry> {
    fun appEntry(label: String, pkg: String?): LiveTileEntry? {
        val key = pkg?.let { p -> appKeys.firstOrNull { it.packageName == p } } ?: return null
        // Wide: the clock's date lines and the calendar's next event need the width.
        return LiveTileEntry(label, TileContent.App(key), TileSize.WIDE)
    }
    return listOfNotNull(
        appEntry("clock", defaults.clock),
        appEntry("calendar", defaults.calendar),
        LiveTileEntry("weather", TileContent.Weather, TileSize.WIDE),
        LiveTileEntry("photos", TileContent.Photos, TileSize.WIDE),
        LiveTileEntry("people", TileContent.People, TileSize.MEDIUM),
    )
}

/**
 * Whether the "tile style" badge is offered for [spec]. The default clock and calendar apps draw
 * their own live face at every size (TileFace.kt: ClockFront, CalendarFront), never the styled
 * icon, so a style or icon picked there would do nothing.
 */
internal fun offersStyle(spec: TileSpec, defaults: DefaultApps): Boolean {
    if (!TileStyles.canStyle(spec)) return false
    val pkg = spec.appKey?.packageName ?: return true
    return pkg != defaults.clock && pkg != defaults.calendar
}
