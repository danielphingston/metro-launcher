package io.github.danielphingston.metro.data

import android.Manifest

/**
 * The smart search features of Metro Plus, in the order the settings list them. Each has its own
 * on/off switch (see [LauncherSettings.smartFeatures]); none shows in results while Metro Plus is
 * locked. Smarter app matching is deliberately not here: it is free and always on.
 *
 * [permission] is asked for only when the user turns the feature on, never at startup.
 */
enum class SmartFeature(
    val id: String,
    val title: String,
    val description: String,
    val defaultOn: Boolean,
    val permission: String? = null,
) {
    CALCULATOR("calculator", "calculator & units", "type 15% of 2400 or 5 km in miles; tap to copy the answer", true),
    SETTINGS_SHORTCUTS("settings_shortcuts", "phone settings shortcuts", "type wifi or battery to jump to that page of the phone's settings", true),
    QUICK_ACTIONS("quick_actions", "quick actions", "timer 5 min, alarm 7:30, note buy milk", true),
    SUGGESTIONS("suggestions", "suggestions before typing", "most used apps and recent searches when search opens", true),
    CALENDAR("calendar", "calendar events", "upcoming events whose title or place matches", false, Manifest.permission.READ_CALENDAR),
    LAUNCHER_ACTIONS("launcher_actions", "launcher actions", "wallpaper, edit tiles, app info and uninstall for an app", true),
    CONTACTS("contacts", "contacts", "find people, then call, message or WhatsApp them", false, Manifest.permission.READ_CONTACTS),
    WHATSAPP("whatsapp", "WhatsApp chats & groups", "open a chat or group by name; needs Metro as the default home app", true),
    SEARCH_IN_APPS("search_in_apps", "search in apps", "search YouTube, Play Store, Maps and other apps for what you typed", true),
    ASK_ASSISTANT("ask_assistant", "ask assistant", "send what you typed to your assistant app", true),
    ;

    companion object {
        /** The features that are on until the user switches them off. */
        val defaults: Set<SmartFeature> = entries.filterTo(LinkedHashSet()) { it.defaultOn }
    }
}
