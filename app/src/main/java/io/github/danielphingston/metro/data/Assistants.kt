package io.github.danielphingston.metro.data

import android.content.Intent
import io.github.danielphingston.metro.live.AppNotifications

/** An assistant app Metro knows by package name. */
data class KnownAssistant(val packageName: String, val label: String)

/** The last question asked through the search row, shown on the assistant tile. */
data class LastAsked(val text: String, val at: Long)

/**
 * Which assistant app the search row and new assistant tiles use. Only package names and the
 * apps the launcher can already see are involved: no APIs, just intents.
 */
object AssistantChoice {
    val KNOWN = listOf(
        KnownAssistant("com.anthropic.claude", "Claude"),
        KnownAssistant("com.openai.chatgpt", "ChatGPT"),
        KnownAssistant("com.google.android.apps.bard", "Gemini"),
        KnownAssistant("ai.perplexity.app.android", "Perplexity"),
        KnownAssistant("com.microsoft.copilot", "Copilot"),
    )

    /** Stored in place of a package: the user switched the assistant off. */
    const val OFF = ""

    /** The known assistants in [installed], in the order of [KNOWN]. */
    fun installedKnown(installed: Set<String>): List<KnownAssistant> = KNOWN.filter { it.packageName in installed }

    /** Claude if installed, else the first installed known assistant, else none. */
    fun defaultPackage(installed: Set<String>): String? = installedKnown(installed).firstOrNull()?.packageName

    /**
     * The assistant in use. [stored] is the saved choice: null while the user never chose (the
     * default applies), [OFF] for off, else a package.
     */
    fun resolve(stored: String?, installed: Set<String>): String? = when (stored) {
        null -> defaultPackage(installed)
        OFF -> null
        else -> stored
    }

    /** The name to show for [packageName]: the known name, else [fallback] (the app's own label). */
    fun displayName(packageName: String, fallback: String): String =
        KNOWN.firstOrNull { it.packageName == packageName }?.label ?: fallback
}

/** How a question reaches an assistant app. */
object AssistantIntents {
    enum class Plan {
        /** The app takes shared text: hand it the question. */
        SEND,

        /** It doesn't: copy the question and open the app, so the user can paste. */
        COPY_AND_OPEN,

        /** Neither is possible (the app is gone). */
        NONE,
    }

    fun plan(canSend: Boolean, canLaunch: Boolean): Plan = when {
        canSend -> Plan.SEND
        canLaunch -> Plan.COPY_AND_OPEN
        else -> Plan.NONE
    }

    /** ACTION_SEND text/plain aimed at one app. */
    fun send(packageName: String, query: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, query)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Used to find the apps that take shared text, with no package. */
    fun sendProbe(): Intent = Intent(Intent.ACTION_SEND).setType("text/plain")

    /** Questions are stored for display only; keep them short. */
    const val MAX_STORED = 300
    fun storable(query: String): String = query.trim().take(MAX_STORED)
}

/** What the assistant tile shows, derived from a package's newest notification. */
data class AssistantModel(
    val appLabel: String,
    val count: Int,
    /** The conversation title, or null when the notification has none worth showing. */
    val title: String?,
    /** The reply text; empty when there is no (visible) notification text. */
    val reply: String,
    val lastAsked: LastAsked?,
    /** When the shown reply was posted, if it is the saved last reply rather than a live notification; null for live content. */
    val readAt: Long? = null,
) {
    /** The reply is the saved one: already read, so no unread count and quieter styling. */
    val isRead: Boolean get() = readAt != null

    /** The question to print next to the reply: for a saved reply only when it was asked before that reply arrived. */
    val footerAsked: LastAsked?
        get() = lastAsked?.takeIf { readAt == null || AssistantLastReply.pairsWithAsked(readAt, it.at) }

    val hasReply: Boolean get() = reply.isNotBlank() || !title.isNullOrBlank()

    /** Medium and larger tiles flip to the last question, but only when there is a reply to flip away from. */
    fun hasBack(size: TileSize): Boolean = size != TileSize.SMALL && hasReply && lastAsked != null

    /** For TalkBack: app, unread count, latest reply title (or the start of the reply). */
    fun description(): String = buildString {
        append(appLabel)
        if (count > 0) append(", $count unread")
        val latest = title?.takeIf { it.isNotBlank() } ?: reply.take(80).takeIf { it.isNotBlank() }
        if (latest != null) append(", latest: $latest")
    }

    companion object {
        /**
         * [data] is the hub's entry for the assistant's package; [showContent] is the user's
         * notification-content setting. SECRET notifications never produce a preview, so they
         * show up here only as a count.
         */
        fun of(
            appLabel: String,
            data: AppNotifications?,
            showContent: Boolean,
            lastAsked: LastAsked?,
            /** The saved last reply, used when there is no live content; see [AssistantLastReply.source]. */
            saved: SavedReply? = null,
            /** The assistant currently in use; a saved reply of another package is ignored. */
            currentPackage: String? = null,
        ): AssistantModel {
            val preview = data?.previews?.firstOrNull()?.takeIf { showContent }
            val text = preview?.let { AssistantText.extract(appLabel, it.title, it.text) }
            val live = AssistantModel(appLabel, data?.count ?: 0, text?.title, text?.reply.orEmpty(), lastAsked)
            return when (AssistantLastReply.source(live.hasReply, saved, currentPackage, showContent)) {
                AssistantLastReply.Source.SAVED -> live.copy(title = saved!!.title, reply = saved.reply, readAt = saved.postTime)
                else -> live
            }
        }
    }
}

/**
 * Assistants format notifications differently: Claude puts the conversation title in the title
 * and the reply in the text, others use their own name as the title. This keeps the title only
 * when it says something, and removes "Claude: " style prefixes.
 */
object AssistantText {
    data class Extracted(val title: String?, val reply: String)

    fun extract(appLabel: String, rawTitle: String, rawText: String): Extracted {
        val title = stripPrefix(appLabel, rawTitle.trim())
        val reply = stripPrefix(appLabel, rawText.trim())
        val titleIsName = title.isBlank() || title.equals(appLabel.trim(), ignoreCase = true)
        return Extracted(if (titleIsName) null else title, reply)
    }

    /** "Claude: hello" and "Claude - hello" become "hello"; a bare name is left to the caller. */
    private fun stripPrefix(appLabel: String, value: String): String {
        val name = appLabel.trim()
        if (name.isEmpty() || value.length <= name.length || !value.startsWith(name, ignoreCase = true)) return value
        val rest = value.substring(name.length).trimStart(' ')
        val separator = rest.firstOrNull() ?: return value
        // A dash only separates when spaced ("Claude - hi"), so "Claude-3 is out" stays whole.
        val spaced = value.getOrNull(name.length) == ' '
        val ok = separator == ':' || (spaced && (separator == '-' || separator == '–' || separator == '—'))
        if (!ok) return value
        return rest.drop(1).trim()
    }
}

/** Finding assistant candidates among the apps the launcher can see. */
object AssistantApps {
    /**
     * Packages of [launchable] apps that take shared plain text (the "other app" choice), without
     * Metro itself and without the known assistants, which have their own entries.
     */
    fun otherSendTargets(context: android.content.Context, launchable: Collection<AppInfo>): List<AppInfo> {
        val accepting = try {
            context.packageManager.queryIntentActivities(AssistantIntents.sendProbe(), 0).map { it.activityInfo.packageName }.toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
        return otherTargets(accepting, launchable.map { it }, context.packageName)
    }

    /** The pure part: [accepting] packages that are launchable, not [self] and not known assistants. */
    fun otherTargets(accepting: Set<String>, launchable: List<AppInfo>, self: String): List<AppInfo> {
        val known = AssistantChoice.KNOWN.map { it.packageName }.toSet()
        return launchable
            .filter { it.key.userSerial == 0L && it.key.packageName in accepting && it.key.packageName != self && it.key.packageName !in known }
            .distinctBy { it.key.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
