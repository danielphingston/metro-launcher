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

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Int = DEFAULT_ACCENT,
    /** 4 = classic Windows Phone grid, 6 = "show more tiles". */
    val columns: Int = 4,
    val monochromeIcons: Boolean = true,
    /** App list rows show the bare icon instead of the icon on an accent square. */
    val appListPlainIcons: Boolean = false,
    /** The backdrop of the alphabet scrubber's big letter. */
    val scrubberHighlight: ScrubberHighlight = ScrubberHighlight.CIRCLE,
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
        val notificationContent = booleanPreferencesKey("notification_content")
        val iconPack = stringPreferencesKey("icon_pack")
        val background = stringPreferencesKey("start_background")
        val backgroundPhoto = stringPreferencesKey("background_photo")
        val backgroundAccent = intPreferencesKey("background_accent")
        val tileTransparency = intPreferencesKey("tile_transparency")
        val accentFromBackground = booleanPreferencesKey("accent_from_background")
        val searchEngine = stringPreferencesKey("search_engine")
        val pullDistance = intPreferencesKey("pull_distance")
    }

    val settings: Flow<LauncherSettings> = context.metroDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): LauncherSettings {
        val defaults = LauncherSettings()
        return LauncherSettings(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            accent = this[Keys.accent] ?: defaults.accent,
            columns = this[Keys.columns] ?: defaults.columns,
            monochromeIcons = this[Keys.monochrome] ?: defaults.monochromeIcons,
            appListPlainIcons = this[Keys.appListPlainIcons] ?: defaults.appListPlainIcons,
            scrubberHighlight = this[Keys.scrubberHighlight]?.let { runCatching { ScrubberHighlight.valueOf(it) }.getOrNull() }
                ?: defaults.scrubberHighlight,
            showNotificationContent = this[Keys.notificationContent] ?: defaults.showNotificationContent,
            iconPack = this[Keys.iconPack]?.takeIf { it.isNotEmpty() },
            background = this[Keys.background]?.let { runCatching { StartBackground.valueOf(it) }.getOrNull() }
                ?: defaults.background,
            backgroundPhoto = this[Keys.backgroundPhoto]?.takeIf { it.isNotEmpty() },
            backgroundAccent = this[Keys.backgroundAccent],
            tileTransparency = this[Keys.tileTransparency],
            accentFromBackground = this[Keys.accentFromBackground] ?: defaults.accentFromBackground,
            searchEngine = this[Keys.searchEngine]?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
                ?: defaults.searchEngine,
            pullDistance = this[Keys.pullDistance]?.coerceIn(LauncherSettings.MIN_PULL_DISTANCE, LauncherSettings.MAX_PULL_DISTANCE)
                ?: defaults.pullDistance,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.metroDataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setAccent(color: Int) = context.metroDataStore.edit { it[Keys.accent] = color }
    suspend fun setColumns(columns: Int) = context.metroDataStore.edit { it[Keys.columns] = columns }
    suspend fun setMonochromeIcons(enabled: Boolean) = context.metroDataStore.edit { it[Keys.monochrome] = enabled }
    suspend fun setAppListPlainIcons(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.appListPlainIcons] = enabled }

    suspend fun setIconPack(packageName: String?) =
        context.metroDataStore.edit { it[Keys.iconPack] = packageName.orEmpty() }

    suspend fun setShowNotificationContent(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.notificationContent] = enabled }

    suspend fun setScrubberHighlight(highlight: ScrubberHighlight) =
        context.metroDataStore.edit { it[Keys.scrubberHighlight] = highlight.name }

    suspend fun setBackground(mode: StartBackground) = context.metroDataStore.edit { it[Keys.background] = mode.name }

    /** Stores the picked photo with the accent derived from it; null [uri] clears both. */
    suspend fun setBackgroundPhoto(uri: String?, accent: Int?) = context.metroDataStore.edit {
        it[Keys.backgroundPhoto] = uri.orEmpty()
        if (accent != null) it[Keys.backgroundAccent] = accent else it.remove(Keys.backgroundAccent)
    }

    suspend fun setTileTransparency(percent: Int) =
        context.metroDataStore.edit { it[Keys.tileTransparency] = percent.coerceIn(0, 100) }

    suspend fun setAccentFromBackground(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.accentFromBackground] = enabled }

    suspend fun setSearchEngine(engine: SearchEngine) =
        context.metroDataStore.edit { it[Keys.searchEngine] = engine.name }

    suspend fun setPullDistance(dp: Int) = context.metroDataStore.edit {
        it[Keys.pullDistance] = dp.coerceIn(LauncherSettings.MIN_PULL_DISTANCE, LauncherSettings.MAX_PULL_DISTANCE)
    }

    /** The photo can't be read any more: forget it and go back to the plain background. */
    suspend fun clearUnreadablePhoto() = context.metroDataStore.edit {
        it[Keys.backgroundPhoto] = ""
        it.remove(Keys.backgroundAccent)
        it[Keys.background] = StartBackground.SOLID.name
    }
}
