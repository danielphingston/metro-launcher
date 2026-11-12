package io.github.danielphingston.metro.ui.search

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.settings.SettingsPage

/** What a launcher-action phrase asks for. */
internal sealed interface LauncherAction {
    /** One of the fixed commands, by [LauncherActions.commands] index. */
    data class Command(val command: LauncherActions.Cmd) : LauncherAction

    /** "app info <name>" or "uninstall <name>": [name] is still to be matched to an app. */
    data class ForApp(val verb: Verb, val name: String) : LauncherAction

    enum class Verb { APP_INFO, UNINSTALL }

    /** "layout <name>": switch start to the saved layout [name] points at. */
    data class SwitchLayout(val name: String) : LauncherAction
}

/** The phrases that act on the launcher itself, and their matching; pure. */
internal object LauncherActions {
    enum class Cmd(val title: String, val keywords: List<String>) {
        WALLPAPER("change wallpaper", listOf("wallpaper", "change wallpaper", "background image")),
        EDIT_TILES("edit tiles", listOf("edit tiles", "edit start", "edit start screen", "rearrange tiles")),
        METRO_SETTINGS("Metro settings", listOf("metro settings", "launcher settings")),
        SMART_SEARCH_SETTINGS("smart search settings", listOf("smart search settings", "search settings", "smart search")),
        LAYOUTS("layouts", listOf("layouts", "start layouts", "layout settings")),
    }

    private val appInfo = Regex("""^(?:app info|app settings|info)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val layout = Regex("""^(?:layout|switch to layout|switch layout)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val uninstall = Regex("""^(?:uninstall|remove app|delete app)\s+(.+)$""", RegexOption.IGNORE_CASE)

    private const val MIN_QUERY = 2
    private const val MAX_APPS = 2

    /** The command phrases [query] matches, best first; a name-taking phrase beats them all. */
    fun parse(query: String): List<LauncherAction> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < MIN_QUERY) return emptyList()
        appInfo.matchEntire(q)?.let { return listOf(LauncherAction.ForApp(LauncherAction.Verb.APP_INFO, it.groupValues[1].trim())) }
        uninstall.matchEntire(q)?.let { return listOf(LauncherAction.ForApp(LauncherAction.Verb.UNINSTALL, it.groupValues[1].trim())) }
        layout.matchEntire(q)?.let { return listOf(LauncherAction.SwitchLayout(it.groupValues[1].trim()), LauncherAction.Command(Cmd.LAYOUTS)) }
        return Cmd.entries
            .mapNotNull { cmd ->
                cmd.keywords.mapNotNull { SearchRanking.rank(it, q) }.filter { it != SearchRanking.CONTAINS }.minOrNull()
                    ?.let { cmd to it }
            }
            .sortedBy { it.second }
            .map { LauncherAction.Command(it.first) }
    }

    /** The apps [name] points at for [verb]; apps that cannot be uninstalled are not offered for uninstalling. */
    fun appsFor(verb: LauncherAction.Verb, name: String, apps: List<AppInfo>): List<AppInfo> {
        val candidates = if (verb == LauncherAction.Verb.UNINSTALL) apps.filter { it.isUninstallable } else apps
        return SearchRanking.top(candidates, name, MAX_APPS) { listOf(it.label) }.map { it.item }
    }

    /**
     * Whether [query] asks for layouts by name: "layouts", "layout settings", "layout work"...
     * Only whole phrases count, so typing an app that starts with "la" never mentions layouts.
     */
    fun asksForLayouts(query: String): Boolean {
        val q = query.trim().replace(Regex("\\s+"), " ").lowercase()
        if (layout.matchEntire(q) != null) return true
        return q == "layout" || q in Cmd.LAYOUTS.keywords
    }

    /** The saved layouts [name] points at, best first. */
    fun layoutsFor(name: String, layouts: List<SavedLayout>): List<SavedLayout> =
        SearchRanking.top(layouts, name, MAX_LAYOUTS) { listOf(it.name) }.map { it.item }

    private const val MAX_LAYOUTS = 3

    /**
     * The search row for [layout]: tapping it runs [switch] in place (nothing is launched, so no app
     * transition plays; the start screen's own switch animation does), and it pins as the real
     * layout switch tile, the same as one added from the add menu.
     */
    fun layoutItem(layout: SavedLayout, activeId: Long, switch: () -> Unit): SearchItem = SearchItem(
        "layout:${layout.id}", "layout: ${layout.name}",
        if (layout.id == activeId) "on start now" else "switch the start screen to it", SearchGlyphs.tiles,
        ItemAction.Run { switch() },
        pin = TileContent.LayoutSwitch(layout.id),
    )
}

/**
 * While Metro Plus is locked (the only time it is asked, see [SearchProviders.active]): a search for
 * layouts gets one row saying they are part of Metro Plus, which opens its settings page, instead
 * of nothing. Asks for nothing else.
 */
internal object LockedLayoutsProvider : SearchProvider {
    override val id = "locked_layouts"
    override val feature: SmartFeature? = null
    override val section = "launcher"

    const val TITLE = "layouts are part of Metro Plus"

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        if (!LauncherActions.asksForLayouts(query)) return emptyList()
        val intent = SettingsActivity.intent(ctx.context, SettingsPage.METRO_PLUS)
        return listOf(
            SearchItem("plus", TITLE, "a start screen for work and one for the weekend, each with its own wallpaper", SearchGlyphs.tiles, SearchActions.start(intent)),
        )
    }
}

/** Feature 7: commands for the launcher itself, plus app info and uninstall for a named app. */
internal class LauncherActionsProvider(
    private val resolves: (Context, Intent) -> Boolean = { context, intent -> context.packageManager.resolveActivity(intent, 0) != null },
) : SearchProvider {
    override val id = "launcher_actions"
    override val feature = SmartFeature.LAUNCHER_ACTIONS
    override val section = "launcher"

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val context = ctx.context
        return LauncherActions.parse(query).flatMap { action ->
            when (action) {
                is LauncherAction.Command -> listOfNotNull(command(action.command, context))
                is LauncherAction.SwitchLayout -> layoutItems(action.name, context)
                is LauncherAction.ForApp -> LauncherActions.appsFor(action.verb, action.name, ctx.apps).map { app ->
                    if (action.verb == LauncherAction.Verb.APP_INFO) {
                        SearchItem(
                            "info:${app.key.serialize()}", "app info: ${app.label}", "open in the phone's settings", SearchIcon.App(app),
                            ItemAction.Run { it.commands.appInfo(app) },
                        )
                    } else {
                        SearchItem(
                            "uninstall:${app.key.serialize()}", "uninstall ${app.label}", "Android asks you to confirm", SearchIcon.App(app),
                            ItemAction.Run { it.commands.uninstall(app) },
                        )
                    }
                }
            }
        }
    }

    /** One row per saved layout matching [name]; tapping it switches start to that layout. */
    private fun layoutItems(name: String, context: Context): List<SearchItem> {
        val app = context.applicationContext as? MetroApplication ?: return emptyList()
        val set = app.layouts.layouts.value?.takeIf { app.layouts.isUnlocked } ?: return emptyList()
        return LauncherActions.layoutsFor(name, set.layouts).map { layout ->
            LauncherActions.layoutItem(layout, set.active) { app.appScope.launch { app.layouts.switchTo(layout.id) } }
        }
    }

    private fun command(cmd: LauncherActions.Cmd, context: Context): SearchItem? = when (cmd) {
        LauncherActions.Cmd.WALLPAPER -> {
            val chooser = Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "wallpaper")
            if (resolves(context, Intent(Intent.ACTION_SET_WALLPAPER))) {
                SearchItem(cmd.name, cmd.title, "choose a wallpaper", SearchGlyphs.wallpaper, SearchActions.start(chooser))
            } else {
                null
            }
        }
        LauncherActions.Cmd.EDIT_TILES ->
            SearchItem(cmd.name, cmd.title, "rearrange, resize and unpin the start screen", SearchGlyphs.tiles, ItemAction.Run { it.commands.editStart() })
        LauncherActions.Cmd.METRO_SETTINGS ->
            SearchItem(
                cmd.name, cmd.title, "open the launcher's settings", SearchGlyphs.gear, SearchActions.start(SettingsActivity.intent(context)),
                pin = SearchActions.pin(cmd.title, SearchGlyphs.gear, SettingsActivity.intent(context)),
            )
        LauncherActions.Cmd.SMART_SEARCH_SETTINGS ->
            SearchItem(
                cmd.name, cmd.title, "choose what search can do", SearchGlyphs.gear,
                SearchActions.start(SettingsActivity.intent(context, SettingsPage.SEARCH)),
                pin = SearchActions.pin(cmd.title, SearchGlyphs.gear, SettingsActivity.intent(context, SettingsPage.SEARCH)),
            )
        LauncherActions.Cmd.LAYOUTS ->
            SearchItem(
                cmd.name, cmd.title, "save and switch start layouts", SearchGlyphs.tiles,
                SearchActions.start(SettingsActivity.intent(context, SettingsPage.LAYOUTS)),
            )
    }
}
