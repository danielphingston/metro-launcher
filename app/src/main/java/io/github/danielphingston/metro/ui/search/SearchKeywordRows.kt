package io.github.danielphingston.metro.ui.search

import android.content.Intent
import android.net.Uri
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.KeywordMatch
import io.github.danielphingston.metro.data.KeywordTarget
import io.github.danielphingston.metro.data.LaunchTarget
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.WebsiteTemplate

/** The highlighted first row of a search keyword. [isWeb] when it is the web search itself, so the plain web row is not repeated. */
internal class KeywordRow(val item: SearchItem, val isWeb: Boolean)

/**
 * Search keywords: turns a [KeywordMatch] into the row that searches its target. Built-in apps,
 * other installed apps and the assistant reuse the intents of "search in apps" and "ask assistant";
 * a website opens its address with ACTION_VIEW, so Android may hand it to the app that owns the domain.
 */
internal object KeywordRows {
    private fun appIcon(apps: List<AppInfo>, packageName: String): SearchIcon.App? =
        apps.firstOrNull { it.key.packageName == packageName && it.key.userSerial == 0L }?.let { SearchIcon.App(it) }
            ?: apps.firstOrNull { it.key.packageName == packageName }?.let { SearchIcon.App(it) }

    /** The picture for [target] in the chip and the settings: the app's own icon when installed, else a drawing. */
    fun icon(target: KeywordTarget, apps: List<AppInfo>, assistantSetting: String? = null): SearchIcon = when (target) {
        is KeywordTarget.BuiltIn -> SearchInApps.icon(apps, target.app)
        KeywordTarget.Web -> SearchGlyphs.search
        KeywordTarget.Assistant -> AssistantTarget.resolve(apps, assistantSetting)?.let { SearchIcon.App(it.app) } ?: SearchGlyphs.chat
        is KeywordTarget.InstalledApp -> appIcon(apps, target.packageName) ?: SearchGlyphs.search
        is KeywordTarget.Website -> SearchGlyphs.globe
    }

    /**
     * Whether a row built for [built] may stay on screen while [now] is active: the same keyword
     * with the typing still going on after it. Another keyword (or none) drops it at once.
     */
    fun stillFits(built: KeywordMatch, now: KeywordMatch?): Boolean =
        now != null && built.entry == now.entry && (built.rest == now.rest || now.rest.isNotBlank())

    /** What the chip says: the target's name ("Amazon"; the assistant's own name for the assistant). */
    fun chipLabel(target: KeywordTarget, apps: List<AppInfo>, assistantSetting: String?): String = when (target) {
        KeywordTarget.Web -> "web"
        KeywordTarget.Assistant -> AssistantTarget.resolve(apps, assistantSetting)?.name ?: target.label
        else -> target.label
    }

    /**
     * The row for [match], searching its rest, or null when the target can't be used right now
     * (the app is gone, no assistant chosen, nothing to search for). Then Enter falls back to the
     * normal results.
     */
    fun build(match: KeywordMatch, ctx: SearchContext, phone: SearchInPhone): KeywordRow? {
        val rest = match.rest.trim()
        if (rest.isEmpty()) return null
        val target = match.entry.target
        val icon = icon(target, ctx.apps, ctx.settings.assistant)
        fun row(title: String, action: ItemAction, subtitle: String? = null, web: Boolean = false, pin: TileContent? = null) =
            KeywordRow(SearchItem(key = "keyword", title = title, subtitle = subtitle, icon = icon, action = action, pin = pin), web)
        // A pinned search repeats it with the same words: the tile is named after them.
        fun pin(intent: Intent) = SearchActions.pin(rest, icon, Intent(intent))
        return when (target) {
            is KeywordTarget.BuiltIn -> SearchInApps.intentFor(target.app, rest, phone, ctx.locale)
                ?.let { row(SearchInApps.title(target.app.label, rest), SearchActions.start(it), pin = pin(it)) }
            KeywordTarget.Web -> {
                val engine = ctx.settings.searchEngine
                row(
                    "search the web for “$rest”",
                    ItemAction.Open { env -> WebSearch.open(env.context, engine.searchUrl(rest), env.bounds, env.options, inBrowser = true) },
                    subtitle = engine.label,
                    web = true,
                    pin = TileContent.Action(rest, icon.actionIcon, LaunchTarget.Web(engine.searchUrl(rest))),
                )
            }
            KeywordTarget.Assistant -> AssistantTarget.resolve(ctx.apps, ctx.settings.assistant)?.let { assistant ->
                row(
                    "ask ${assistant.name}",
                    ItemAction.Open { env ->
                        // Only a question that was actually sent is remembered for the assistant tile.
                        val sent = AssistantAsk.ask(env.context, assistant.app.key.packageName, rest, env.bounds, env.options)
                        if (sent) env.commands.recordAssistantQuestion(rest)
                        sent
                    },
                    subtitle = rest,
                )
            }
            is KeywordTarget.InstalledApp -> SearchInApps.intentForOther(target.packageName, rest, phone)
                ?.let { row(SearchInApps.title(target.label, rest), SearchActions.start(it), pin = pin(it)) }
            is KeywordTarget.Website -> WebsiteTemplate.build(target.template, rest)?.let { url ->
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                row(SearchInApps.title(target.name, rest), SearchActions.start(view), pin = pin(view))
            }
        }
    }
}
