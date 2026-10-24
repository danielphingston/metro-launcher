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

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Int = DEFAULT_ACCENT,
    /** 4 = classic Windows Phone grid, 6 = "show more tiles". */
    val columns: Int = 4,
    val monochromeIcons: Boolean = true,
    val showNotificationContent: Boolean = true,
    /** Package of the selected icon pack, or null for the apps' own icons. */
    val iconPack: String? = null,
) {
    companion object {
        const val DEFAULT_ACCENT = 0xFF1BA1E2.toInt() // cyan
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
}
