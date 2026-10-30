package io.github.danielphingston.metro.data

/**
 * The apps "search in apps" can search, built in. Each has its own switch (see
 * [LauncherSettings.searchInApps]); apps found at run time that accept a search are switched
 * separately by package (see [LauncherSettings.searchInOtherApps]). A built-in app that isn't
 * installed is skipped, except where a web page can stand in (YouTube, Amazon).
 */
enum class SearchInApp(val id: String, val label: String, val packageName: String, val defaultOn: Boolean) {
    YOUTUBE("youtube", "YouTube", "com.google.android.youtube", true),
    PLAY_STORE("play_store", "Play Store", "com.android.vending", true),
    MAPS("maps", "Maps", "com.google.android.apps.maps", false),
    SPOTIFY("spotify", "Spotify", "com.spotify.music", false),
    AMAZON("amazon", "Amazon", "com.amazon.mShop.android.shopping", false),
    ;

    companion object {
        /** The built-in apps that are on until the user switches them off. */
        val defaults: Set<SearchInApp> = entries.filterTo(LinkedHashSet()) { it.defaultOn }
    }
}
