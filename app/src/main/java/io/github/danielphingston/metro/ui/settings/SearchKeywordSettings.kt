package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.KeywordTarget
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ListFilter
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchKeyword
import io.github.danielphingston.metro.data.SearchKeywords
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.WebsiteTemplate
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroSwitch
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.SearchField
import io.github.danielphingston.metro.ui.components.SearchFieldStyle
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.search.KeywordIcon
import io.github.danielphingston.metro.ui.search.KeywordRows
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** A keyword being added ([original] null) or edited; the settings page shows the editor while one is open. */
internal class KeywordEdit(val original: SearchKeyword?)

/**
 * The keywords page's list: one row per keyword matching [filter] (target icon, "a → Amazon", its
 * own switch; tap to edit), then "add keyword" and "reset to defaults".
 */
@Composable
internal fun SearchKeywordList(
    settings: LauncherSettings,
    repository: SettingsRepository,
    apps: List<AppInfo>,
    icons: IconCache?,
    onEdit: (KeywordEdit) -> Unit,
    filter: String = "",
) {
    val scope = rememberCoroutineScope()
    Column {
        val shown = settings.searchKeywords.filter { keywordMatches(it, filter) }
        shown.forEach { entry ->
            KeywordRow(
                entry,
                apps,
                icons,
                settings.assistant,
                onClick = { onEdit(KeywordEdit(entry)) },
                onToggle = { on ->
                    scope.launch { repository.setSearchKeywordEnabled(entry.keyword, on) }
                },
            )
        }
        if (settings.searchKeywords.isEmpty()) {
            MetroText("no keywords; add one or reset to the defaults", MetroType.caption, color = Metro.colors.subtle)
        } else if (shown.isEmpty()) {
            MetroText("no keyword matches “${filter.trim()}”", MetroType.caption, color = Metro.colors.subtle)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetroButton("add keyword", onClick = { onEdit(KeywordEdit(null)) })
            MetroButton("reset to defaults", onClick = { scope.launch { repository.resetSearchKeywords() } })
        }
    }
}

/** Whether [entry] answers the typed [filter]: by keyword, by its summary or by where it searches. */
internal fun keywordMatches(entry: SearchKeyword, filter: String) =
    ListFilter.matches(filter, entry.keyword, entry.summary, entry.target.label, targetNote(entry.target))

@Composable
private fun KeywordRow(
    entry: SearchKeyword,
    apps: List<AppInfo>,
    icons: IconCache?,
    assistant: String?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val colors = Metro.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .metroTilt()
                .clickable(role = Role.Button, onClickLabel = "edit", onClick = onClick)
                .padding(vertical = 8.dp)
                .alpha(if (entry.enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (icons != null) KeywordIcon(KeywordRows.icon(entry.target, apps, assistant), icons, 28.dp, colors.foreground)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                MetroText(entry.summary, MetroType.body, maxLines = 1)
                MetroText(targetNote(entry.target), MetroType.caption, color = colors.subtle, maxLines = 1)
            }
        }
        MetroSwitch(
            entry.enabled,
            Modifier
                .clickable(role = Role.Switch) { onToggle(!entry.enabled) }
                .semantics {
                    contentDescription = "${entry.keyword} keyword"
                    stateDescription = if (entry.enabled) "on" else "off"
                },
        )
    }
}

/** The grey line under a keyword: what kind of target it is. */
internal fun targetNote(target: KeywordTarget): String = when (target) {
    is KeywordTarget.BuiltIn -> "search in apps"
    KeywordTarget.Web -> "your default search engine"
    KeywordTarget.Assistant -> "your assistant app"
    is KeywordTarget.InstalledApp -> "installed app"
    is KeywordTarget.Website -> WebsiteTemplate.hostOf(target.template)
}

/**
 * The add / edit page of a keyword: the keyword with its checks, where it searches (an app, the
 * web, the assistant, an installed app that takes a search, or a website with its address
 * template) and, when editing, delete. [keywords] are all the keywords, for the uniqueness check.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchKeywordEditor(
    original: SearchKeyword?,
    keywords: List<SearchKeyword>,
    otherApps: List<OtherSearchApp>,
    onSave: (SearchKeyword) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    initialText: String? = null,
) {
    val colors = Metro.colors
    var text by remember { mutableStateOf(initialText ?: original?.keyword.orEmpty()) }
    val originalTarget = original?.target
    var picked by remember { mutableStateOf(originalTarget.takeIf { it !is KeywordTarget.Website }) }
    var site by remember { mutableStateOf(originalTarget is KeywordTarget.Website) }
    var siteName by remember { mutableStateOf((originalTarget as? KeywordTarget.Website)?.name.orEmpty()) }
    var template by remember { mutableStateOf((originalTarget as? KeywordTarget.Website)?.template.orEmpty()) }

    val keywordError = SearchKeywords.validate(text, keywords, original?.keyword)
    val templateError = if (site) WebsiteTemplate.validate(template) else null
    val target: KeywordTarget? = when {
        site -> if (templateError == null) {
            KeywordTarget.Website(siteName.trim().ifBlank { WebsiteTemplate.hostOf(template) }, template.trim())
        } else {
            null
        }
        else -> picked
    }
    val canSave = keywordError == null && target != null

    Spacer(Modifier.height(36.dp))
    MetroText(if (original == null) "add keyword" else "edit keyword", MetroType.sectionTitle)
    Spacer(Modifier.height(4.dp))
    MetroText(
        "Type the keyword and a space before what you search for, such as “a headphones”.",
        MetroType.caption,
        color = colors.subtle,
    )

    Spacer(Modifier.height(16.dp))
    MetroText("keyword", MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(6.dp))
    SearchField(text, { text = it.take(24) }, placeholder = "keyword", style = SearchFieldStyle.OUTLINED)
    // Nothing nags before the first letter; an empty field only keeps the button off.
    if (text.isNotEmpty() && keywordError != null) {
        Spacer(Modifier.height(4.dp))
        MetroText(keywordError.message, MetroType.caption, color = colors.accent)
    }

    Spacer(Modifier.height(20.dp))
    MetroText("searches in", MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fun choose(t: KeywordTarget) {
            site = false
            picked = t
        }
        SearchInApp.entries.forEach { app ->
            val t = KeywordTarget.BuiltIn(app)
            MetroChoice(app.label, !site && picked == t, { choose(t) })
        }
        MetroChoice("web search", !site && picked == KeywordTarget.Web, { choose(KeywordTarget.Web) })
        MetroChoice("assistant", !site && picked == KeywordTarget.Assistant, { choose(KeywordTarget.Assistant) })
        // An app that is no longer installed stays selectable while it is the one being edited.
        val installed = otherApps.map { KeywordTarget.InstalledApp(it.packageName, it.label) }
        val kept = (originalTarget as? KeywordTarget.InstalledApp)?.takeIf { o -> installed.none { it.packageName == o.packageName } }
        (listOfNotNull(kept) + installed).forEach { t ->
            MetroChoice(t.label, !site && (picked as? KeywordTarget.InstalledApp)?.packageName == t.packageName, { choose(t) })
        }
        MetroChoice("custom website", site, { site = true })
    }

    if (site) {
        Spacer(Modifier.height(20.dp))
        MetroText("ready-made", MetroType.caption, color = colors.subtle)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebsiteTemplate.presets.forEach { preset ->
                MetroChoice(preset.name, template == preset.template, { siteName = preset.name; template = preset.template })
            }
        }
        Spacer(Modifier.height(16.dp))
        MetroText("name", MetroType.caption, color = colors.subtle)
        Spacer(Modifier.height(6.dp))
        SearchField(siteName, { siteName = it.take(30) }, placeholder = "name, such as Flipkart", style = SearchFieldStyle.OUTLINED)
        Spacer(Modifier.height(16.dp))
        MetroText("search address, with %s where the text goes", MetroType.caption, color = colors.subtle)
        Spacer(Modifier.height(6.dp))
        SearchField(template, { template = it }, placeholder = "https://example.com/search?q=%s", style = SearchFieldStyle.OUTLINED)
        if (template.isNotEmpty() && templateError != null) {
            Spacer(Modifier.height(4.dp))
            MetroText(templateError, MetroType.caption, color = colors.accent)
        }
        Spacer(Modifier.height(4.dp))
        MetroText(
            "The text is url-encoded. The address opens like any link, so Android may hand it to the matching app.",
            MetroType.caption,
            color = colors.subtle,
        )
    }

    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroButton(
            "save",
            onClick = { if (canSave && target != null) onSave(SearchKeyword(text.trim(), target, original?.enabled ?: true)) },
            modifier = Modifier.alpha(if (canSave) 1f else 0.4f),
        )
        MetroButton("cancel", onClick = onCancel)
        if (original != null) MetroButton("delete", onClick = onDelete)
    }
}
