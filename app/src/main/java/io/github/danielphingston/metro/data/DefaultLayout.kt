package io.github.danielphingston.metro.data

/** First-run start screen, modelled on a fresh Windows Phone 8.1 with the user's default apps. */
object DefaultLayout {
    fun build(apps: List<AppInfo>, defaults: DefaultApps, ownPackage: String): List<TileSpec> {
        val wanted = listOf(
            defaults.dialer to TileSize.MEDIUM,
            defaults.messaging to TileSize.MEDIUM,
            defaults.email to TileSize.WIDE,
            defaults.calendar to TileSize.WIDE,
            defaults.clock to TileSize.MEDIUM,
            defaults.browser to TileSize.MEDIUM,
            defaults.gallery to TileSize.WIDE,
            defaults.camera to TileSize.SMALL,
            "com.android.vending" to TileSize.SMALL,
            "com.android.settings" to TileSize.SMALL,
            ownPackage to TileSize.SMALL,
            defaults.music to TileSize.MEDIUM,
        )
        val seen = HashSet<String>()
        var id = 1L
        return wanted.mapNotNull { (pkg, size) ->
            if (pkg == null || !seen.add(pkg)) return@mapNotNull null
            // Prefer the personal profile's copy of the app.
            val app = apps.filter { it.key.packageName == pkg }.minByOrNull { it.key.userSerial }
                ?: return@mapNotNull null
            TileSpec(id++, size, TileContent.App(app.key))
        }
    }
}
