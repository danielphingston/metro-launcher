package io.github.danielphingston.metro.ui.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.danielphingston.metro.data.SmartFeature

/** A page of the phone's settings that a keyword leads to. [action] is the Settings intent; [component] replaces it where no public action exists. */
internal class SettingsShortcut(
    val title: String,
    val keywords: List<String>,
    val action: String? = null,
    val component: ComponentName? = null,
    val needsDeveloperOptions: Boolean = false,
) {
    fun intent(): Intent =
        (if (component != null) Intent().setComponent(component) else Intent(action)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** The keyword table and the matching, with no Android calls so they can be tested. */
internal object SettingsShortcuts {
    private const val SETTINGS = "com.android.settings"

    val all: List<SettingsShortcut> = listOf(
        SettingsShortcut("wi-fi", listOf("wifi", "wi-fi", "wireless", "wlan"), Settings.ACTION_WIFI_SETTINGS),
        SettingsShortcut("bluetooth", listOf("bluetooth", "bt"), Settings.ACTION_BLUETOOTH_SETTINGS),
        SettingsShortcut("battery", listOf("battery", "power usage", "charging"), Intent.ACTION_POWER_USAGE_SUMMARY),
        SettingsShortcut("battery saver", listOf("battery saver", "power saving", "low power"), Settings.ACTION_BATTERY_SAVER_SETTINGS),
        SettingsShortcut("display & brightness", listOf("display", "brightness", "screen", "font size", "dark mode"), Settings.ACTION_DISPLAY_SETTINGS),
        SettingsShortcut("night light", listOf("night light", "night mode", "blue light"), Settings.ACTION_NIGHT_DISPLAY_SETTINGS),
        SettingsShortcut("auto-rotate", listOf("auto rotate", "rotation", "rotate screen"), Settings.ACTION_AUTO_ROTATE_SETTINGS),
        SettingsShortcut("sound & vibration", listOf("sound", "volume", "ringtone", "vibration"), Settings.ACTION_SOUND_SETTINGS),
        SettingsShortcut("notifications", listOf("notifications", "notification settings"), Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS),
        SettingsShortcut("do not disturb", listOf("do not disturb", "dnd", "silent mode", "priority mode"), Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        SettingsShortcut("storage", listOf("storage", "free space", "disk space"), Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        SettingsShortcut("apps", listOf("apps", "applications", "manage apps", "app permissions"), Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        SettingsShortcut("default apps", listOf("default apps", "default browser", "default app"), Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        SettingsShortcut("home app", listOf("home app", "default launcher", "default home"), Settings.ACTION_HOME_SETTINGS),
        SettingsShortcut("location", listOf("location", "gps"), Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SettingsShortcut("security", listOf("security", "screen lock", "lock screen", "fingerprint", "password"), Settings.ACTION_SECURITY_SETTINGS),
        SettingsShortcut("privacy", listOf("privacy", "permissions manager"), Settings.ACTION_PRIVACY_SETTINGS),
        SettingsShortcut("accessibility", listOf("accessibility", "talkback", "magnification"), Settings.ACTION_ACCESSIBILITY_SETTINGS),
        SettingsShortcut("date & time", listOf("date", "time", "time zone", "date and time"), Settings.ACTION_DATE_SETTINGS),
        SettingsShortcut("language", listOf("language", "languages", "region"), Settings.ACTION_LOCALE_SETTINGS),
        SettingsShortcut("keyboard", listOf("keyboard", "input method", "typing"), Settings.ACTION_INPUT_METHOD_SETTINGS),
        SettingsShortcut("developer options", listOf("developer options", "developer", "usb debugging", "adb"), Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, needsDeveloperOptions = true),
        SettingsShortcut("mobile data", listOf("mobile data", "data usage", "cellular", "data saver"), Settings.ACTION_DATA_USAGE_SETTINGS),
        SettingsShortcut("mobile network", listOf("mobile network", "sim", "carrier", "roaming"), Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        SettingsShortcut("network & internet", listOf("network", "internet", "connections"), Settings.ACTION_WIRELESS_SETTINGS),
        SettingsShortcut("airplane mode", listOf("airplane mode", "flight mode", "aeroplane"), Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        SettingsShortcut("hotspot & tethering", listOf("hotspot", "tethering", "wifi hotspot"), component = ComponentName(SETTINGS, "$SETTINGS.TetherSettings")),
        SettingsShortcut("nfc", listOf("nfc", "tap to pay", "contactless"), Settings.ACTION_NFC_SETTINGS),
        SettingsShortcut("vpn", listOf("vpn"), Settings.ACTION_VPN_SETTINGS),
        SettingsShortcut("accounts & sync", listOf("accounts", "sync", "add account"), Settings.ACTION_SYNC_SETTINGS),
        SettingsShortcut("cast", listOf("cast", "screen mirroring", "mirror screen"), Settings.ACTION_CAST_SETTINGS),
        SettingsShortcut("about phone", listOf("about phone", "about", "device info", "android version", "build number", "phone info"), Settings.ACTION_DEVICE_INFO_SETTINGS),
        SettingsShortcut("phone settings", listOf("settings", "phone settings", "system settings"), Settings.ACTION_SETTINGS),
    )

    private const val MAX_RESULTS = 3
    private const val MIN_QUERY = 2

    /** How well [query] matches [shortcut]: its best rank over the title and keywords, or null. Containing the query anywhere is not enough. */
    fun rank(shortcut: SettingsShortcut, query: String): Int? =
        (shortcut.keywords + shortcut.title).mapNotNull { SearchRanking.rank(it, query) }
            .filter { it != SearchRanking.CONTAINS }
            .minOrNull()

    /**
     * Up to three shortcuts for [query], best first and in table order among equals, that [available]
     * accepts (the caller checks that the intent resolves and that developer options are on).
     */
    fun match(query: String, available: (SettingsShortcut) -> Boolean): List<SettingsShortcut> {
        if (query.trim().length < MIN_QUERY) return emptyList()
        return all.mapNotNull { s -> rank(s, query)?.let { s to it } }
            .sortedBy { it.second } // stable: table order within a rank
            .map { it.first }
            .filter(available)
            .take(MAX_RESULTS)
    }
}

/** Feature 2: keyword to a page of the phone's settings, only for pages that exist on this phone. */
internal class SettingsShortcutsProvider(
    private val resolves: (Context, Intent) -> Boolean = { context, intent -> context.packageManager.resolveActivity(intent, 0) != null },
    private val developerOptionsOn: (Context) -> Boolean = { context ->
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    },
) : SearchProvider {
    override val id = "settings_shortcuts"
    override val feature = SmartFeature.SETTINGS_SHORTCUTS
    override val section = "phone settings"

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val context = ctx.context
        val developer by lazy { developerOptionsOn(context) }
        return SettingsShortcuts.match(query) { s ->
            (!s.needsDeveloperOptions || developer) && resolves(context, s.intent())
        }.map { s ->
            SearchItem(
                key = s.title,
                title = s.title,
                subtitle = "open in the phone's settings",
                icon = SearchGlyphs.gear,
                action = ItemAction.Start(s.intent()),
            )
        }
    }
}
