package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.SmartFeature

/**
 * Feature 11: the "ask <assistant>" row that sends the typed text to the chosen assistant app.
 * It was the search overlay's own row before smart search; now it is a provider like the others,
 * after the web row, and only while Metro Plus is unlocked and its switch is on.
 */
internal object AskAssistantProvider : SearchProvider {
    override val id = "ask_assistant"
    override val feature = SmartFeature.ASK_ASSISTANT
    override val placement = Placement.BOTTOM

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val target = AssistantTarget.resolve(ctx.apps, ctx.settings.assistant) ?: return emptyList()
        return listOf(
            SearchItem(
                key = "ask",
                title = "ask ${target.name}",
                subtitle = query,
                icon = SearchIcon.App(target.app),
                action = ItemAction.Open { env ->
                    env.commands.recordAssistantQuestion(query)
                    AssistantAsk.ask(env.context, target.app.key.packageName, query, env.bounds, env.options)
                },
            ),
        )
    }
}

/** The assistant app the ask row sends to; [name] is the label to show ("Claude"). */
internal data class AssistantTarget(val app: AppInfo, val name: String) {
    companion object {
        /** The app [setting] (see [AssistantChoice.resolve]) points at among the launchable [apps], or null when there is none. */
        fun resolve(apps: List<AppInfo>, setting: String?): AssistantTarget? {
            val pkg = AssistantChoice.resolve(setting, apps.mapTo(HashSet()) { it.key.packageName }) ?: return null
            val app = apps.firstOrNull { it.key.packageName == pkg && it.key.userSerial == 0L }
                ?: apps.firstOrNull { it.key.packageName == pkg }
                ?: return null
            return AssistantTarget(app, AssistantChoice.displayName(app.key.packageName, app.label))
        }
    }
}
