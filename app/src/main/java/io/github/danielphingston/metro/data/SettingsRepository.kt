package io.github.danielphingston.metro.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Int = DEFAULT_ACCENT,
    /** 4 = classic Windows Phone grid, 6 = "show more tiles". */
    val columns: Int = 4,
    val monochromeIcons: Boolean = true,
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
    }
}

internal val Context.metroDataStore by preferencesDataStore(name = "metro")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val accent = intPreferencesKey("accent")
        val columns = intPreferencesKey("columns")
        val monochrome = booleanPreferencesKey("monochrome_icons")
        val notificationContent = booleanPreferencesKey("notification_content")
        val iconPack = stringPreferencesKey("icon_pack")
        val background = stringPreferencesKey("start_background")
        val backgroundPhoto = stringPreferencesKey("background_photo")
        val backgroundAccent = intPreferencesKey("background_accent")
        val tileTransparency = intPreferencesKey("tile_transparency")
        val accentFromBackground = booleanPreferencesKey("accent_from_background")
    }

    val settings: Flow<LauncherSettings> = context.metroDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): LauncherSettings {
        val defaults = LauncherSettings()
        return LauncherSettings(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            accent = this[Keys.accent] ?: defaults.accent,
            columns = this[Keys.columns] ?: defaults.columns,
            monochromeIcons = this[Keys.monochrome] ?: defaults.monochromeIcons,
            showNotificationContent = this[Keys.notificationContent] ?: defaults.showNotificationContent,
            iconPack = this[Keys.iconPack]?.takeIf { it.isNotEmpty() },
            background = this[Keys.background]?.let { runCatching { StartBackground.valueOf(it) }.getOrNull() }
                ?: defaults.background,
            backgroundPhoto = this[Keys.backgroundPhoto]?.takeIf { it.isNotEmpty() },
            backgroundAccent = this[Keys.backgroundAccent],
            tileTransparency = this[Keys.tileTransparency],
            accentFromBackground = this[Keys.accentFromBackground] ?: defaults.accentFromBackground,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.metroDataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setAccent(color: Int) = context.metroDataStore.edit { it[Keys.accent] = color }
    suspend fun setColumns(columns: Int) = context.metroDataStore.edit { it[Keys.columns] = columns }
    suspend fun setMonochromeIcons(enabled: Boolean) = context.metroDataStore.edit { it[Keys.monochrome] = enabled }
    suspend fun setIconPack(packageName: String?) =
        context.metroDataStore.edit { it[Keys.iconPack] = packageName.orEmpty() }

    suspend fun setShowNotificationContent(enabled: Boolean) =
        context.metroDataStore.edit { it[Keys.notificationContent] = enabled }

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

    /** The photo can't be read any more: forget it and go back to the plain background. */
    suspend fun clearUnreadablePhoto() = context.metroDataStore.edit {
        it[Keys.backgroundPhoto] = ""
        it.remove(Keys.backgroundAccent)
        it[Keys.background] = StartBackground.SOLID.name
    }
}
