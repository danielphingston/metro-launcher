package io.github.danielphingston.metro.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

/** How the start screen and app list animate away when an app is launched (and back when you return). */
enum class LaunchStyle(val label: String, val description: String) {
    /** Windows Phone: tiles swing about the left edge on a diagonal stagger, the tapped one last. */
    TURNSTILE("turnstile", "tiles swing away one after another, the tapped one last"),

    /** Windows Phone order: whole rows swing together, top to bottom, the tapped element last. */
    TURNSTILE_ROWS("turnstile by rows", "whole rows swing away together, top to bottom"),

    /** Elements slide down and fade, row by row. */
    SLIDE("slide", "tiles slide down and fade, row by row"),

    /** Elements shrink toward the tapped one and fade, all together. */
    ZOOM("zoom", "everything shrinks toward the tapped tile and fades"),

    /** Everything fades together. */
    FADE("fade", "everything fades out together"),

    /** No launcher animation: the system reveals the app from the tapped element. */
    NONE("none", "no animation of the launcher, the app opens from the tapped tile"),
}

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Int = DEFAULT_ACCENT,
    /** 4 = classic Windows Phone grid, 6 = "show more tiles". */
    val columns: Int = AUTO_COLUMNS,
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
    /** The animation that plays when an app is launched. */
    val launchStyle: LaunchStyle = LaunchStyle.TURNSTILE,
    /** Percent of normal speed for the launcher's own animations: 100 is as designed, 200 twice as fast. */
    val animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
    /** Play the launch animation backwards when the launcher comes back. */
    val animateReturn: Boolean = true,
    /** Start the app while the launcher is still animating away, so its startup overlaps the animation. */
    val openDuringAnimation: Boolean = true,
    /** Tiles and rows tip toward the finger when pressed. */
    val tiltEnabled: Boolean = true,
    /** The most a pressed element tips, in degrees. */
    val tiltDegrees: Int = DEFAULT_TILT_DEGREES,
    /** Live tiles flip over by themselves. */
    val liveTileFlips: Boolean = true,
    /** The average time (seconds) between one live tile flipping and the next. */
    val flipIntervalSeconds: Int = DEFAULT_FLIP_INTERVAL,
    /** How many recent searches the empty search remembers and shows. */
    val searchHistorySize: Int = DEFAULT_SEARCH_HISTORY,
    /** The start screen and app list lean while the pager is dragged between them. */
    val swingPages: Boolean = true,
    /** The assistant app: null while the user never chose, [AssistantChoice.OFF] for off, else a package; see [AssistantChoice.resolve]. */
    val assistant: String? = null,
    /** The last question sent to an assistant from the search row. */
    val lastAsked: LastAsked? = null,
    /** The smart search features that are switched on; they only act while Metro Plus is unlocked. */
    val smartFeatures: Set<SmartFeature> = SmartFeature.defaults,
    /** The built-in apps "search in apps" offers; it only acts while that smart feature is on. */
    val searchInApps: Set<SearchInApp> = SearchInApp.defaults,
    /** Packages of other installed apps that accept a search and are switched on for "search in apps". */
    val searchInOtherApps: Set<String> = emptySet(),
    /** The search keywords ("a" for Amazon); they only act while the SEARCH_KEYWORDS smart feature is on. */
    val searchKeywords: List<SearchKeyword> = SearchKeywords.defaults,
) {
    /** Whether [feature]'s own switch is on (it still needs Metro Plus to show anything). */
    fun smartOn(feature: SmartFeature): Boolean = feature in smartFeatures

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

        /**
         * Stored in place of a column count: as many columns as keep the tiles about a phone's
         * size across the width (see StartColumns). Only large screens use it; phones show
         * [DEFAULT_COLUMNS]. It is also what an install that never chose gets.
         */
        const val AUTO_COLUMNS = 0

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

        const val MIN_ANIMATION_SPEED = 50
        const val MAX_ANIMATION_SPEED = 200
        const val DEFAULT_ANIMATION_SPEED = 100

        const val MIN_TILT_DEGREES = 3
        const val MAX_TILT_DEGREES = 15
        const val DEFAULT_TILT_DEGREES = 9

        const val MIN_SEARCH_HISTORY = 1
        const val MAX_SEARCH_HISTORY = 25
        const val DEFAULT_SEARCH_HISTORY = 5

        const val MIN_FLIP_INTERVAL = 2
        const val MAX_FLIP_INTERVAL = 15
        const val DEFAULT_FLIP_INTERVAL = 4

        // Stored values can be out of range (an older build, a damaged file): these pull them back in.
        /** [AUTO_COLUMNS], or an even column count (tiles pair up) between [MIN_COLUMNS] and [MAX_COLUMNS]. */
        fun clampColumns(columns: Int) =
            if (columns == AUTO_COLUMNS) AUTO_COLUMNS else (columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS) + 1) / 2 * 2
        fun clampTransparency(percent: Int) = percent.coerceIn(0, 100)
        fun clampPullDistance(dp: Int) = dp.coerceIn(MIN_PULL_DISTANCE, MAX_PULL_DISTANCE)
        fun clampScrubberPosition(percent: Int) = percent.coerceIn(MIN_SCRUBBER_POSITION, MAX_SCRUBBER_POSITION)
        fun clampStartTopPadding(dp: Int) = dp.coerceIn(MIN_START_TOP_PADDING, MAX_START_TOP_PADDING)
        fun clampStartBottomPadding(dp: Int) = dp.coerceIn(MIN_START_BOTTOM_PADDING, MAX_START_BOTTOM_PADDING)
        fun clampStartSidePadding(dp: Int) = dp.coerceIn(MIN_START_SIDE_PADDING, MAX_START_SIDE_PADDING)
        fun clampAnimationSpeed(percent: Int) = percent.coerceIn(MIN_ANIMATION_SPEED, MAX_ANIMATION_SPEED)
        fun clampTiltDegrees(degrees: Int) = degrees.coerceIn(MIN_TILT_DEGREES, MAX_TILT_DEGREES)
        fun clampSearchHistory(count: Int) = count.coerceIn(MIN_SEARCH_HISTORY, MAX_SEARCH_HISTORY)
        fun clampFlipInterval(seconds: Int) = seconds.coerceIn(MIN_FLIP_INTERVAL, MAX_FLIP_INTERVAL)

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
        val launchStyle = stringPreferencesKey("launch_style")
        val animationSpeed = intPreferencesKey("animation_speed")
        val animateReturn = booleanPreferencesKey("animate_return")
        val openDuringAnimation = booleanPreferencesKey("open_during_animation")
        val tiltEnabled = booleanPreferencesKey("tilt_enabled")
        val tiltDegrees = intPreferencesKey("tilt_degrees")
        val liveTileFlips = booleanPreferencesKey("live_tile_flips")
        val flipInterval = intPreferencesKey("flip_interval")
        val searchHistorySize = intPreferencesKey("search_history_size")
        val swingPages = booleanPreferencesKey("swing_pages")
        val assistant = stringPreferencesKey("assistant_package")
        val lastQuestion = stringPreferencesKey("assistant_last_question")
        val lastAskedAt = longPreferencesKey("assistant_last_asked_at")

        /** One switch per smart search feature; a missing key means the feature's default. */
        fun smart(feature: SmartFeature) = booleanPreferencesKey("smart_${feature.id}")

        /** One switch per built-in "search in apps" app; a missing key means its default. */
        fun searchInApp(app: SearchInApp) = booleanPreferencesKey("search_in_${app.id}")
        val searchInOtherApps = stringSetPreferencesKey("search_in_other_apps")

        /** The keywords as JSON (see [SearchKeywords.encode]); missing means the defaults. */
        val searchKeywords = stringPreferencesKey("search_keywords")
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
            launchStyle = this[Keys.launchStyle]?.let { runCatching { LaunchStyle.valueOf(it) }.getOrNull() }
                ?: defaults.launchStyle,
            animationSpeed = this[Keys.animationSpeed]?.let(LauncherSettings::clampAnimationSpeed)
                ?: defaults.animationSpeed,
            animateReturn = this[Keys.animateReturn] ?: defaults.animateReturn,
            openDuringAnimation = this[Keys.openDuringAnimation] ?: defaults.openDuringAnimation,
            tiltEnabled = this[Keys.tiltEnabled] ?: defaults.tiltEnabled,
            tiltDegrees = this[Keys.tiltDegrees]?.let(LauncherSettings::clampTiltDegrees) ?: defaults.tiltDegrees,
            liveTileFlips = this[Keys.liveTileFlips] ?: defaults.liveTileFlips,
            flipIntervalSeconds = this[Keys.flipInterval]?.let(LauncherSettings::clampFlipInterval)
                ?: defaults.flipIntervalSeconds,
            searchHistorySize = this[Keys.searchHistorySize]?.let(LauncherSettings::clampSearchHistory)
                ?: defaults.searchHistorySize,
            swingPages = this[Keys.swingPages] ?: defaults.swingPages,
            assistant = this[Keys.assistant],
            lastAsked = this[Keys.lastQuestion]?.takeIf { it.isNotBlank() }
                ?.let { LastAsked(it, this[Keys.lastAskedAt] ?: 0L) },
            smartFeatures = SmartFeature.entries.filterTo(LinkedHashSet()) { this[Keys.smart(it)] ?: it.defaultOn },
            searchInApps = SearchInApp.entries.filterTo(LinkedHashSet()) { this[Keys.searchInApp(it)] ?: it.defaultOn },
            searchInOtherApps = this[Keys.searchInOtherApps].orEmpty(),
            searchKeywords = SearchKeywords.decode(this[Keys.searchKeywords]),
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

    suspend fun setLaunchStyle(style: LaunchStyle) = context.metroDataStore.edit { it[Keys.launchStyle] = style.name }

    suspend fun setAnimationSpeed(percent: Int) = context.metroDataStore.edit {
        it[Keys.animationSpeed] = LauncherSettings.clampAnimationSpeed(percent)
    }

    suspend fun setAnimateReturn(enabled: Boolean) = context.metroDataStore.edit { it[Keys.animateReturn] = enabled }

    suspend fun setOpenDuringAnimation(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.openDuringAnimation] = enabled }

    suspend fun setTiltEnabled(enabled: Boolean) = context.metroDataStore.edit { it[Keys.tiltEnabled] = enabled }

    suspend fun setTiltDegrees(degrees: Int) = context.metroDataStore.edit {
        it[Keys.tiltDegrees] = LauncherSettings.clampTiltDegrees(degrees)
    }

    suspend fun setLiveTileFlips(enabled: Boolean) = context.metroDataStore.edit { it[Keys.liveTileFlips] = enabled }

    suspend fun setFlipInterval(seconds: Int) = context.metroDataStore.edit {
        it[Keys.flipInterval] = LauncherSettings.clampFlipInterval(seconds)
    }

    suspend fun setSearchHistorySize(count: Int) = context.metroDataStore.edit {
        it[Keys.searchHistorySize] = LauncherSettings.clampSearchHistory(count)
    }

    /** [packageName] null switches the assistant off; a package chooses it. Either ends the automatic default. */
    suspend fun setAssistant(packageName: String?) =
        context.metroDataStore.edit { it[Keys.assistant] = packageName ?: AssistantChoice.OFF }

    suspend fun setLastAsked(question: String, at: Long) = context.metroDataStore.edit {
        it[Keys.lastQuestion] = AssistantIntents.storable(question)
        it[Keys.lastAskedAt] = at
    }

    suspend fun setSmartFeature(feature: SmartFeature, enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.smart(feature)] = enabled }

    suspend fun setSearchInApp(app: SearchInApp, enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.searchInApp(app)] = enabled }

    suspend fun setSearchInOtherApp(packageName: String, enabled: Boolean) = context.metroDataStore.edit {
        val current = it[Keys.searchInOtherApps].orEmpty()
        it[Keys.searchInOtherApps] = if (enabled) current + packageName else current - packageName
    }

    suspend fun setSearchKeywords(keywords: List<SearchKeyword>) =
        context.metroDataStore.edit { it[Keys.searchKeywords] = SearchKeywords.encode(keywords) }

    suspend fun resetSearchKeywords() = context.metroDataStore.edit { it.remove(Keys.searchKeywords) }

    suspend fun setSwingPages(enabled: Boolean) = context.metroDataStore.edit { it[Keys.swingPages] = enabled }

    /** The photo can't be read any more: forget it and go back to the plain background. */
    suspend fun clearUnreadablePhoto() = context.metroDataStore.edit {
        it[Keys.backgroundPhoto] = ""
        it.remove(Keys.backgroundAccent)
        it[Keys.background] = StartBackground.SOLID.name
    }
}
