package io.github.danielphingston.metro.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.KeywordMatch
import io.github.danielphingston.metro.data.KeywordTarget
import io.github.danielphingston.metro.data.WebsiteTemplate

/** The highlighted first row of a search keyword. [isWeb] when it is the web search itself, so the plain web row is not repeated. */
internal class KeywordRow(val item: SearchItem, val isWeb: Boolean)

/**
 * Search keywords: turns a [KeywordMatch] into the row that searches its target. Built-in apps,
 * other installed apps and the assistant reuse the intents of "search in apps" and "ask assistant";
 * a website opens its address with ACTION_VIEW, so Android may hand it to the app that owns the domain.
 */
internal object KeywordRows {
    /** A globe for a website with no app icon of its own. */
    val globe = SearchIcon.Glyph("globe") { color ->
        val stroke = 1.5.dp.toPx()
        val r = size.minDimension * 0.42f
        val c = center
        drawCircle(color, r, c, style = Stroke(stroke))
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke, StrokeCap.Round)
        drawOval(color, Offset(c.x - r * 0.45f, c.y - r), androidx.compose.ui.geometry.Size(r * 0.9f, r * 2), style = Stroke(stroke))
    }

    private fun appIcon(apps: List<AppInfo>, packageName: String): SearchIcon.App? =
        apps.firstOrNull { it.key.packageName == packageName && it.key.userSerial == 0L }?.let { SearchIcon.App(it) }
            ?: apps.firstOrNull { it.key.packageName == packageName }?.let { SearchIcon.App(it) }

    /** The picture for [target] in the chip and the settings: the app's own icon when installed, else a drawing. */
    fun icon(target: KeywordTarget, apps: List<AppInfo>, assistantSetting: String? = null): SearchIcon = when (target) {
        is KeywordTarget.BuiltIn -> SearchInApps.icon(apps, target.app)
        KeywordTarget.Web -> SearchGlyphs.search
        KeywordTarget.Assistant -> AssistantTarget.resolve(apps, assistantSetting)?.let { SearchIcon.App(it.app) } ?: SearchGlyphs.chat
        is KeywordTarget.InstalledApp -> appIcon(apps, target.packageName) ?: SearchGlyphs.search
        is KeywordTarget.Website -> globe
    }

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
        fun row(title: String, action: ItemAction, subtitle: String? = null, web: Boolean = false) =
            KeywordRow(SearchItem(key = "keyword", title = title, subtitle = subtitle, icon = icon, action = action), web)
        return when (target) {
            is KeywordTarget.BuiltIn -> SearchInApps.intentFor(target.app, rest, phone, ctx.locale)
                ?.let { row(SearchInApps.title(target.app.label, rest), SearchActions.start(it)) }
            KeywordTarget.Web -> {
                val engine = ctx.settings.searchEngine
                row(
                    "search the web for “$rest”",
                    ItemAction.Open { env -> WebSearch.open(env.context, engine.searchUrl(rest), env.bounds, env.options, inBrowser = true) },
                    subtitle = engine.label,
                    web = true,
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
                ?.let { row(SearchInApps.title(target.label, rest), SearchActions.start(it)) }
            is KeywordTarget.Website -> WebsiteTemplate.build(target.template, rest)?.let { url ->
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                row(SearchInApps.title(target.name, rest), SearchActions.start(view))
            }
        }
    }
}
