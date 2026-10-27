package io.github.danielphingston.metro.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** What shows behind the tiles on the start screen. */
enum class StartBackground {
    /** The plain black or white page. */
    SOLID,

    /** Windows Phone 8.1: each tile is a window onto one photo fixed to the screen. */
    PHOTO_TILES,

    /** Windows 10 Mobile: the photo fills the start screen, tiles are translucent accent. */
    PHOTO_FULL,

    /** The phone's own wallpaper, composited by the system behind a transparent window. */
    SYSTEM_WALLPAPER,
}

/** What sits behind the big letter that follows the finger on the app list's alphabet scrubber. */
enum class ScrubberHighlight(val label: String) {
    /** Just the letter. */
    NONE("none"),

    /** A soft-edged accent disc. */
    CIRCLE("circle"),

    /** A soft-edged, generously padded accent rounded rectangle. */
    ROUNDED_RECT("rounded rectangle"),
}

/** Where the scrubber's big letter shows while the finger is down. */
enum class ScrubberHighlightPosition(val label: String) {
    /** One fixed spot at eye level, beside the strip, not following the finger. */
    FIXED("fixed spot"),

    /** Beside the finger, following it up and down the strip. */
    BESIDE_FINGER("beside finger"),
}

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Int = DEFAULT_ACCENT,
    /** 4 = classic Windows Phone grid, 6 = "show more tiles". */
    val columns: Int = DEFAULT_COLUMNS,
    val monochromeIcons: Boolean = true,
    /** App list rows show the bare icon instead of the icon on an accent square. */
    val appListPlainIcons: Boolean = false,
    /** The backdrop of the alphabet scrubber's big letter. */
    val scrubberHighlight: ScrubberHighlight = ScrubberHighlight.CIRCLE,
    /** Where the scrubber's big letter appears. */
    val scrubberHighlightPosition: ScrubberHighlightPosition = ScrubberHighlightPosition.FIXED,
    /** The gap (percent of the list's height) between the scrubber strip and the bottom of the list. */
    val scrubberPosition: Int = DEFAULT_SCRUBBER_POSITION,
    val showNotificationContent: Boolean = true,
    /** Package of the selected icon pack, or null for the apps' own icons. */
    val iconPack: String? = null,
    val background: StartBackground = StartBackground.SOLID,
    /** Persisted URI of the photo picked for the photo backgrounds. */
    val backgroundPhoto: String? = null,
    /** Vivid colour derived from [backgroundPhoto] when it was picked. */
    val backgroundAccent: Int? = null,
    /** Percent of the tiles' accent layer that lets the background through; null means the mode's default. */
    val tileTransparency: Int? = null,
    /** Use [backgroundAccent] instead of [accent] while a photo background is on. */
    val accentFromBackground: Boolean = false,
    /** Where the search overlay's "web" row searches. */
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    /** How far (dp) the start screen must be pulled down, then released, to open search. */
    val pullDistance: Int = DEFAULT_PULL_DISTANCE,
    /** The wi-fi quick tile names the network; needs the precise location permission. */
    /** The gap (dp) between the status bar and the first row of tiles on the start screen. */
    val startTopPadding: Int = DEFAULT_START_TOP_PADDING,
    /** The gap (dp) between the last row of buttons and the navigation bar. */
    val startBottomPadding: Int = DEFAULT_START_BOTTOM_PADDING,
    /** The gaps (dp) between the screen's left and right edges and the tiles. */
    val startLeftPadding: Int = DEFAULT_START_LEFT_PADDING,
    val startRightPadding: Int = DEFAULT_START_RIGHT_PADDING,
    /** The round buttons under the tiles take the accent colour instead of the quiet foreground. */
    val accentArrows: Boolean = false,
    val showWifiName: Boolean = false,
    /** The bluetooth quick tile lists connected devices; needs the nearby devices permission. */
    val showBluetoothDevices: Boolean = false,
) {
    /** Whether the start screen shows the picked photo. */
    val showsPhoto: Boolean get() = background == StartBackground.PHOTO_TILES || background == StartBackground.PHOTO_FULL

    /** The accent in use: the one derived from the photo when asked for, else the chosen one. */
    val effectiveAccent: Int
        get() = if (showsPhoto && accentFromBackground && backgroundAccent != null) backgroundAccent else accent

    /** 0..1: how much of the background shows through the tiles. */
    val transparencyFraction: Float
        get() = (tileTransparency ?: if (background == StartBackground.SOLID) 0 else DEFAULT_PHOTO_TRANSPARENCY) / 100f

    companion object {
        const val DEFAULT_ACCENT = 0xFF1BA1E2.toInt() // cyan

        /** Photo and wallpaper backgrounds need some transparency to be seen at all. */
        const val DEFAULT_PHOTO_TRANSPARENCY = 45

        const val MIN_PULL_DISTANCE = 40
        const val MAX_PULL_DISTANCE = 200
        const val DEFAULT_PULL_DISTANCE = 72

        const val MIN_COLUMNS = 4
        const val MAX_COLUMNS = 8
        const val DEFAULT_COLUMNS = 4

        const val MIN_START_TOP_PADDING = 0
        const val MAX_START_TOP_PADDING = 96
        const val DEFAULT_START_TOP_PADDING = 28

        const val MIN_START_BOTTOM_PADDING = 0
        const val MAX_START_BOTTOM_PADDING = 96
        const val DEFAULT_START_BOTTOM_PADDING = 16

        const val MIN_START_SIDE_PADDING = 0
        const val MAX_START_SIDE_PADDING = 48
        const val DEFAULT_START_LEFT_PADDING = 8
        const val DEFAULT_START_RIGHT_PADDING = 8

        // Stored values can be out of range (an older build, a damaged file): these pull them back in.
        /** An even column count (tiles pair up) between [MIN_COLUMNS] and [MAX_COLUMNS]. */
        fun clampColumns(columns: Int) = (columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS) + 1) / 2 * 2
        fun clampTransparency(percent: Int) = percent.coerceIn(0, 100)
        fun clampPullDistance(dp: Int) = dp.coerceIn(MIN_PULL_DISTANCE, MAX_PULL_DISTANCE)
        fun clampScrubberPosition(percent: Int) = percent.coerceIn(MIN_SCRUBBER_POSITION, MAX_SCRUBBER_POSITION)
        fun clampStartTopPadding(dp: Int) = dp.coerceIn(MIN_START_TOP_PADDING, MAX_START_TOP_PADDING)
        fun clampStartBottomPadding(dp: Int) = dp.coerceIn(MIN_START_BOTTOM_PADDING, MAX_START_BOTTOM_PADDING)
        fun clampStartSidePadding(dp: Int) = dp.coerceIn(MIN_START_SIDE_PADDING, MAX_START_SIDE_PADDING)

        /** Colours are drawn as stored; a transparent one would be invisible, so force it opaque. */
        fun opaque(color: Int) = color or 0xFF000000.toInt()

        const val MIN_SCRUBBER_POSITION = 0
        const val MAX_SCRUBBER_POSITION = 40
        const val DEFAULT_SCRUBBER_POSITION = 12
    }
}

/**
 * A damaged file (a write cut short by a crash or a full disk) starts over with the defaults
 * instead of throwing on every read, which would crash the launcher on every start.
 */
internal val Context.metroDataStore by preferencesDataStore(
    name = "metro",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val accent = intPreferencesKey("accent")
        val columns = intPreferencesKey("columns")
        val monochrome = booleanPreferencesKey("monochrome_icons")
        val appListPlainIcons = booleanPreferencesKey("app_list_plain_icons")
        val scrubberHighlight = stringPreferencesKey("scrubber_highlight")
        val scrubberHighlightPosition = stringPreferencesKey("scrubber_highlight_position")
        val scrubberPosition = intPreferencesKey("scrubber_position")
        val notificationContent = booleanPreferencesKey("notification_content")
        val iconPack = stringPreferencesKey("icon_pack")
        val background = stringPreferencesKey("start_background")
        val backgroundPhoto = stringPreferencesKey("background_photo")
        val backgroundAccent = intPreferencesKey("background_accent")
        val tileTransparency = intPreferencesKey("tile_transparency")
        val accentFromBackground = booleanPreferencesKey("accent_from_background")
        val searchEngine = stringPreferencesKey("search_engine")
        val pullDistance = intPreferencesKey("pull_distance")
        val startTopPadding = intPreferencesKey("start_top_padding")
        val startBottomPadding = intPreferencesKey("start_bottom_padding")
        val startLeftPadding = intPreferencesKey("start_left_padding")
        val startRightPadding = intPreferencesKey("start_right_padding")
        val accentArrows = booleanPreferencesKey("accent_arrows")
        val showWifiName = booleanPreferencesKey("show_wifi_name")
        val showBluetoothDevices = booleanPreferencesKey("show_bluetooth_devices")
    }

    val settings: Flow<LauncherSettings> = context.metroDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): LauncherSettings {
        val defaults = LauncherSettings()
        return LauncherSettings(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            accent = this[Keys.accent]?.let(LauncherSettings::opaque) ?: defaults.accent,
            columns = this[Keys.columns]?.let(LauncherSettings::clampColumns) ?: defaults.columns,
            monochromeIcons = this[Keys.monochrome] ?: defaults.monochromeIcons,
            appListPlainIcons = this[Keys.appListPlainIcons] ?: defaults.appListPlainIcons,
            scrubberHighlight = this[Keys.scrubberHighlight]?.let { runCatching { ScrubberHighlight.valueOf(it) }.getOrNull() }
                ?: defaults.scrubberHighlight,
            scrubberHighlightPosition = this[Keys.scrubberHighlightPosition]
                ?.let { runCatching { ScrubberHighlightPosition.valueOf(it) }.getOrNull() }
                ?: defaults.scrubberHighlightPosition,
            scrubberPosition = this[Keys.scrubberPosition]?.let(LauncherSettings::clampScrubberPosition)
                ?: defaults.scrubberPosition,
            showNotificationContent = this[Keys.notificationContent] ?: defaults.showNotificationContent,
            iconPack = this[Keys.iconPack]?.takeIf { it.isNotEmpty() },
            background = this[Keys.background]?.let { runCatching { StartBackground.valueOf(it) }.getOrNull() }
                ?: defaults.background,
            backgroundPhoto = this[Keys.backgroundPhoto]?.takeIf { it.isNotEmpty() },
            backgroundAccent = this[Keys.backgroundAccent]?.let(LauncherSettings::opaque),
            tileTransparency = this[Keys.tileTransparency]?.let(LauncherSettings::clampTransparency),
            accentFromBackground = this[Keys.accentFromBackground] ?: defaults.accentFromBackground,
            searchEngine = this[Keys.searchEngine]?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
                ?: defaults.searchEngine,
            pullDistance = this[Keys.pullDistance]?.let(LauncherSettings::clampPullDistance) ?: defaults.pullDistance,
            startTopPadding = this[Keys.startTopPadding]?.let(LauncherSettings::clampStartTopPadding)
                ?: defaults.startTopPadding,
            startBottomPadding = this[Keys.startBottomPadding]?.let(LauncherSettings::clampStartBottomPadding)
                ?: defaults.startBottomPadding,
            startLeftPadding = this[Keys.startLeftPadding]?.let(LauncherSettings::clampStartSidePadding)
                ?: defaults.startLeftPadding,
            startRightPadding = this[Keys.startRightPadding]?.let(LauncherSettings::clampStartSidePadding)
                ?: defaults.startRightPadding,
            accentArrows = this[Keys.accentArrows] ?: defaults.accentArrows,
            showWifiName = this[Keys.showWifiName] ?: defaults.showWifiName,
            showBluetoothDevices = this[Keys.showBluetoothDevices] ?: defaults.showBluetoothDevices,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.metroDataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setAccent(color: Int) = context.metroDataStore.edit { it[Keys.accent] = LauncherSettings.opaque(color) }
    suspend fun setColumns(columns: Int) = context.metroDataStore.edit { it[Keys.columns] = LauncherSettings.clampColumns(columns) }
    suspend fun setMonochromeIcons(enabled: Boolean) = context.metroDataStore.edit { it[Keys.monochrome] = enabled }
    suspend fun setAppListPlainIcons(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.appListPlainIcons] = enabled }

    suspend fun setIconPack(packageName: String?) =
        context.metroDataStore.edit { it[Keys.iconPack] = packageName.orEmpty() }

    suspend fun setShowNotificationContent(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.notificationContent] = enabled }

    suspend fun setScrubberHighlight(highlight: ScrubberHighlight) =
        context.metroDataStore.edit { it[Keys.scrubberHighlight] = highlight.name }

    suspend fun setScrubberHighlightPosition(position: ScrubberHighlightPosition) =
        context.metroDataStore.edit { it[Keys.scrubberHighlightPosition] = position.name }

    suspend fun setScrubberPosition(percent: Int) = context.metroDataStore.edit {
        it[Keys.scrubberPosition] = LauncherSettings.clampScrubberPosition(percent)
    }

    suspend fun setBackground(mode: StartBackground) = context.metroDataStore.edit { it[Keys.background] = mode.name }

    /** Stores the picked photo with the accent derived from it; null [uri] clears both. */
    suspend fun setBackgroundPhoto(uri: String?, accent: Int?) = context.metroDataStore.edit {
        it[Keys.backgroundPhoto] = uri.orEmpty()
        if (accent != null) it[Keys.backgroundAccent] = LauncherSettings.opaque(accent) else it.remove(Keys.backgroundAccent)
    }

    suspend fun setTileTransparency(percent: Int) =
        context.metroDataStore.edit { it[Keys.tileTransparency] = LauncherSettings.clampTransparency(percent) }

    suspend fun setAccentFromBackground(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.accentFromBackground] = enabled }

    suspend fun setSearchEngine(engine: SearchEngine) =
        context.metroDataStore.edit { it[Keys.searchEngine] = engine.name }

    suspend fun setPullDistance(dp: Int) = context.metroDataStore.edit {
        it[Keys.pullDistance] = LauncherSettings.clampPullDistance(dp)
    }

    suspend fun setStartTopPadding(dp: Int) = context.metroDataStore.edit {
        it[Keys.startTopPadding] = LauncherSettings.clampStartTopPadding(dp)
    }

    suspend fun setStartBottomPadding(dp: Int) = context.metroDataStore.edit {
        it[Keys.startBottomPadding] = LauncherSettings.clampStartBottomPadding(dp)
    }

    suspend fun setStartLeftPadding(dp: Int) = context.metroDataStore.edit {
        it[Keys.startLeftPadding] = LauncherSettings.clampStartSidePadding(dp)
    }

    suspend fun setStartRightPadding(dp: Int) = context.metroDataStore.edit {
        it[Keys.startRightPadding] = LauncherSettings.clampStartSidePadding(dp)
    }

    suspend fun setAccentArrows(enabled: Boolean) = context.metroDataStore.edit { it[Keys.accentArrows] = enabled }

    suspend fun setShowWifiName(enabled: Boolean) = context.metroDataStore.edit { it[Keys.showWifiName] = enabled }

    suspend fun setShowBluetoothDevices(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.showBluetoothDevices] = enabled }

    /** The photo can't be read any more: forget it and go back to the plain background. */
    suspend fun clearUnreadablePhoto() = context.metroDataStore.edit {
        it[Keys.backgroundPhoto] = ""
        it.remove(Keys.backgroundAccent)
        it[Keys.background] = StartBackground.SOLID.name
    }
}
