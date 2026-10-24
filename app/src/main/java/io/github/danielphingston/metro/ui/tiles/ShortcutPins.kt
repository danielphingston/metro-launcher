package io.github.danielphingston.metro.ui.tiles

/**
 * Which shortcut ids to hand to `LauncherApps.pinShortcuts`. That call replaces a package's
 * whole pinned set for this launcher, so every change starts from the ids already pinned.
 */
internal object ShortcutPins {
    /** [pinned] plus [id], keeping order and dropping duplicates. */
    fun added(pinned: Collection<String>, id: String): List<String> = (pinned + id).distinct()

    /** [pinned] without [id]. */
    fun removed(pinned: Collection<String>, id: String): List<String> = pinned.filter { it != id }.distinct()
}
