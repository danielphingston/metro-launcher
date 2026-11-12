package io.github.danielphingston.metro.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Process
import io.github.danielphingston.metro.BuildConfig
import io.github.danielphingston.metro.R
import io.github.danielphingston.metro.live.UserSerials
import io.github.danielphingston.metro.ui.SwitchLayoutActivity
import io.github.danielphingston.metro.ui.settings.SettingsActivity

/**
 * Lets other apps switch the start layout: one app shortcut per saved layout, and the link
 * `metro://layout/<name or id>` (`metro://layout/next` for the one after the active layout).
 *
 * Automation apps (Samsung Modes and Routines, Tasker, MacroDroid, Assistant routines) can open an
 * app shortcut or a link but know nothing about Metro, so this is the way a "work" mode can put
 * the work layout on start. Nothing runs in the background for it: the shortcuts are rewritten
 * only when the saved layouts change.
 */
object LayoutShortcuts {
    const val SCHEME = "metro"
    const val HOST = "layout"
    const val NEXT = "next"
    private const val ID_PREFIX = "layout_"

    /** What a shortcut or link asks for. */
    sealed interface Target {
        data object Next : Target
        data class Layout(val id: Long) : Target
    }

    /** The user serial of the user Metro runs as: only that user's layout shortcuts are this launcher's. */
    fun ownSerial(context: Context): Long = UserSerials.of(context, Process.myUserHandle())

    /**
     * The saved layout an app shortcut switches to, when it is one of Metro's own layout shortcuts
     * ([ownPackage], id "layout_<id>", published by this user: [userSerial] is [ownSerial]; a Metro
     * in a work profile publishes its own for its own layouts), else null. Such a shortcut is handled inside the launcher
     * (search, a pinned tile, pinning) like a switch tile, instead of being started as an app:
     * starting it went through the invisible [SwitchLayoutActivity] in a task of its own, with the
     * launch and return animations around it, and took seconds.
     */
    fun layoutIdOf(
        packageName: String,
        shortcutId: String,
        userSerial: Long,
        ownSerial: Long,
        ownPackage: String = BuildConfig.APPLICATION_ID,
    ): Long? = if (packageName == ownPackage && userSerial == ownSerial && shortcutId.startsWith(ID_PREFIX)) {
        shortcutId.removePrefix(ID_PREFIX).toLongOrNull()
    } else {
        null
    }

    /** [content] as a layout switch tile when it is one of Metro's own layout shortcuts (see [layoutIdOf]), else unchanged. */
    fun asLayoutSwitch(content: TileContent, ownSerial: Long, ownPackage: String = BuildConfig.APPLICATION_ID): TileContent =
        (content as? TileContent.Shortcut)?.let { layoutIdOf(it.packageName, it.shortcutId, it.userSerial, ownSerial, ownPackage) }
            ?.let { TileContent.LayoutSwitch(it) } ?: content

    /** [asLayoutSwitch] for the user Metro runs as. */
    fun asLayoutSwitch(context: Context, content: TileContent): TileContent =
        if (content is TileContent.Shortcut) asLayoutSwitch(content, ownSerial(context)) else content

    fun uri(id: Long): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(id.toString()).build()

    /**
     * The layout [segment] (the link's path: an id, a name, or "next") points at in [set]. A name
     * wins over an id that happens to look the same, and "next" is only the command when no layout
     * is called that. Null when nothing matches.
     */
    fun resolve(segment: String?, set: LayoutSet?): Target? {
        val wanted = segment?.trim().orEmpty()
        if (wanted.isEmpty() || set == null) return null
        set.layouts.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return Target.Layout(it.id) }
        wanted.toLongOrNull()?.let { id -> set.byId(id)?.let { return Target.Layout(it.id) } }
        return if (wanted.equals(NEXT, ignoreCase = true)) Target.Next else null
    }

    /** The layouts that get a shortcut, in order: all of them when there is a choice to make, at most [max]. */
    fun shortcutLayouts(set: LayoutSet?, max: Int): List<SavedLayout> =
        if (set == null || set.layouts.size < 2) emptyList() else set.layouts.take(max.coerceAtLeast(0))

    /** Replaces Metro's layout shortcuts with one per layout in [set]; other dynamic shortcuts are left alone. */
    fun sync(context: Context, set: LayoutSet?) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val wanted = shortcutLayouts(set, manager.maxShortcutCountPerActivity).map { layout ->
                ShortcutInfo.Builder(context, ID_PREFIX + layout.id)
                    .setShortLabel(layout.name)
                    .setLongLabel("switch to ${layout.name}")
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    // Shortcuts hang off the app's launcher icon, which is Metro settings.
                    .setActivity(ComponentName(context, SettingsActivity::class.java))
                    .setIntent(Intent(Intent.ACTION_VIEW, uri(layout.id), context, SwitchLayoutActivity::class.java))
                    .build()
            }
            val stale = manager.dynamicShortcuts.map { it.id }.filter { id -> id.startsWith(ID_PREFIX) && wanted.none { it.id == id } }
            if (stale.isNotEmpty()) manager.removeDynamicShortcuts(stale)
            if (wanted.isNotEmpty()) manager.addDynamicShortcuts(wanted)
        }
    }
}
