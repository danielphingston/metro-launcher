package io.github.danielphingston.metro.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ListFilter
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchInOrder
import io.github.danielphingston.metro.data.SearchInOrdering
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroSwitch
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.SearchField
import io.github.danielphingston.metro.ui.components.SearchFieldStyle
import io.github.danielphingston.metro.ui.search.KeywordIcon
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.search.SearchInApps
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The two long lists of the search settings, each on its own page with a filter field on top:
 * "search in apps" (every app the results can search, with the order of the strip) and "search
 * keywords". The *Host composables read what the page needs from the app; the page composables
 * below take everything as parameters so they can be rendered on their own.
 */

/** The apps, icons and unlock state the two pages need, read from the app. */
@Composable
private fun searchPageData(): Triple<List<AppInfo>, IconCache, Boolean> {
    val context = LocalContext.current
    val metro = context.applicationContext as MetroApplication
    val apps by metro.apps.apps.collectAsState()
    val unlocked by metro.purchases.quickTilesUnlocked.collectAsState()
    return Triple(apps.orEmpty(), metro.icons, unlocked)
}

@Composable
internal fun SearchInAppsPageHost(settings: LauncherSettings, repository: SettingsRepository, onOpenMetroPlus: () -> Unit) {
    val context = LocalContext.current
    val (apps, icons, unlocked) = searchPageData()
    // Which other apps accept a search is a package manager query: ask off the main thread, once the apps are known.
    val others by produceState(emptyList<OtherSearchApp>(), apps, unlocked) {
        value = if (unlocked) withContext(Dispatchers.Default) { SearchInApps.otherApps(context, apps) } else emptyList()
    }
    SearchInAppsPage(settings, repository, unlocked, others, apps, icons, onOpenMetroPlus)
}

@Composable
internal fun SearchKeywordsPageHost(settings: LauncherSettings, repository: SettingsRepository, onOpenMetroPlus: () -> Unit) {
    val context = LocalContext.current
    val (apps, icons, unlocked) = searchPageData()
    val others by produceState(emptyList<OtherSearchApp>(), apps, unlocked) {
        value = if (unlocked) withContext(Dispatchers.Default) { SearchInApps.otherApps(context, apps) } else emptyList()
    }
    SearchKeywordsPage(settings, repository, unlocked, others, apps, icons, onOpenMetroPlus)
}

/** What a locked page shows: the feature, locked like in the smart search list, and the way to unlock it. */
@Composable
private fun LockedPage(title: String, description: String, onUnlock: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        LockGlyph(Metro.colors.subtle, Modifier.padding(top = 4.dp).size(width = 20.dp, height = 22.dp))
        Column(Modifier.weight(1f)) {
            MetroText(title, MetroType.body)
            MetroText(description, MetroType.caption, color = Metro.colors.subtle)
        }
    }
    Spacer(Modifier.height(12.dp))
    MetroButton("unlock with Metro Plus", onClick = onUnlock)
}

/** One entry of the search-in-apps list. */
private class SearchInEntry(
    val id: String,
    val label: String,
    val note: String?,
    val icon: io.github.danielphingston.metro.ui.search.SearchIcon,
    val on: Boolean,
    val toggle: suspend (Boolean) -> Unit,
)

/**
 * The "search in apps" page: the filter, the strip's order (most used, or custom with up / down
 * buttons) and every app the results can search, each with its switch. [initialFilter] is only for
 * rendering the page with text typed.
 */
@Composable
internal fun SearchInAppsPage(
    settings: LauncherSettings,
    repository: SettingsRepository,
    unlocked: Boolean,
    others: List<OtherSearchApp>,
    apps: List<AppInfo>,
    icons: IconCache?,
    onUnlock: () -> Unit,
    initialFilter: String = "",
) {
    if (!unlocked) {
        LockedPage("search in apps", "search YouTube, Play Store, Maps, Spotify, Amazon and other apps for what you typed", onUnlock)
        return
    }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(initialFilter) }
    val entries = SearchInApp.entries.map { app ->
        SearchInEntry(
            SearchInOrdering.builtInId(app), app.label, searchInAppNote(app), SearchInApps.icon(apps, app),
            app in settings.searchInApps,
        ) { repository.setSearchInApp(app, it) }
    } + others.map { other ->
        SearchInEntry(
            SearchInOrdering.otherId(other.packageName), other.label, null,
            SearchInApps.installedApp(apps, listOf(other.packageName))?.let { io.github.danielphingston.metro.ui.search.SearchIcon.App(it) } ?: SearchGlyphs.search,
            other.packageName in settings.searchInOtherApps,
        ) { repository.setSearchInOtherApp(other.packageName, it) }
    }
    val known = entries.map { it.id }
    val custom = settings.searchInOrder == SearchInOrder.CUSTOM
    // Custom order: the stored one, completed with the apps it does not know yet; otherwise the natural order.
    val shownOrder = if (custom) SearchInOrdering.complete(settings.searchInCustomOrder, known) else known
    val ordered = shownOrder.mapNotNull { id -> entries.firstOrNull { it.id == id } }
    val filtering = filter.isNotBlank()
    val shown = ordered.filter { ListFilter.matches(filter, it.label) }

    Spacer(Modifier.height(16.dp))
    SearchField(filter, { filter = it }, placeholder = "filter apps", style = SearchFieldStyle.OUTLINED)

    Section("order")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchInOrder.entries.forEach { order ->
            MetroChoice(order.label, settings.searchInOrder == order, { scope.launch { repository.setSearchInOrder(order) } })
        }
    }
    Spacer(Modifier.height(8.dp))
    MetroText(
        if (custom) {
            "Results show the apps in the order below. Use the arrows to move one" +
                (if (filtering) " (clear the filter first)." else ".")
        } else {
            "Results show the app you search in most often first; apps used equally often keep the custom order."
        },
        MetroType.caption,
        color = Metro.colors.subtle,
    )

    Section("apps")
    shown.forEach { entry ->
        val index = ordered.indexOf(entry)
        SearchInRow(
            entry,
            icons,
            onToggle = { scope.launch { entry.toggle(it) } },
            onMove = if (custom && !filtering) { delta -> scope.launch { repository.moveSearchInTarget(entry.id, delta, known) } } else null,
            canUp = index > 0,
            canDown = index < ordered.lastIndex,
        )
    }
    if (shown.isEmpty()) MetroText("no app matches “${filter.trim()}”", MetroType.caption, color = Metro.colors.subtle)
}

@Composable
private fun SearchInRow(
    entry: SearchInEntry,
    icons: IconCache?,
    onToggle: (Boolean) -> Unit,
    onMove: ((Int) -> Unit)?,
    canUp: Boolean,
    canDown: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).padding(vertical = 8.dp).alpha(if (entry.on) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (icons != null) KeywordIcon(entry.icon, icons, 28.dp, Metro.colors.foreground)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                MetroText(entry.label, MetroType.body, maxLines = 1)
                entry.note?.let { MetroText(it, MetroType.caption, color = Metro.colors.subtle, maxLines = 2) }
            }
        }
        if (onMove != null) {
            ArrowButton(up = true, enabled = canUp, label = "move ${entry.label} up") { onMove(-1) }
            ArrowButton(up = false, enabled = canDown, label = "move ${entry.label} down") { onMove(1) }
        }
        Spacer(Modifier.width(4.dp))
        MetroSwitch(
            entry.on,
            Modifier
                .clickable(role = Role.Switch) { onToggle(!entry.on) }
                .semantics {
                    contentDescription = "search in ${entry.label}"
                    stateDescription = if (entry.on) "on" else "off"
                },
        )
    }
}

/** A 44dp touch target with a hairline chevron; dimmed and inert at the end of the list. */
@Composable
private fun ArrowButton(up: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val color = Metro.colors.foreground
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.25f)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) { drawChevron(color, up) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChevron(color: Color, up: Boolean) {
    val stroke = 1.5.dp.toPx()
    val top = if (up) size.height * 0.3f else size.height * 0.7f
    val base = if (up) size.height * 0.7f else size.height * 0.3f
    drawLine(color, Offset(size.width * 0.1f, base), Offset(size.width / 2, top), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.9f, base), Offset(size.width / 2, top), stroke, StrokeCap.Round)
}

/**
 * The "search keywords" page: the filter, the keywords matching it (switch each; tap to edit), then
 * add and reset. The keyword editor takes the place of the page while a keyword is added or edited.
 */
@Composable
internal fun SearchKeywordsPage(
    settings: LauncherSettings,
    repository: SettingsRepository,
    unlocked: Boolean,
    others: List<OtherSearchApp>,
    apps: List<AppInfo>,
    icons: IconCache?,
    onUnlock: () -> Unit,
    initialFilter: String = "",
) {
    if (!unlocked) {
        LockedPage("search keywords", "start with a short word and a space, such as a for Amazon or yt for YouTube, to search there", onUnlock)
        return
    }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(initialFilter) }
    var editing by remember { mutableStateOf<KeywordEdit?>(null) }
    BackHandler(enabled = editing != null) { editing = null }
    editing?.let { edit ->
        SearchKeywordEditor(
            original = edit.original,
            keywords = settings.searchKeywords,
            otherApps = others,
            onSave = { saved ->
                scope.launch { repository.saveSearchKeyword(edit.original?.keyword, saved) }
                editing = null
            },
            onDelete = {
                edit.original?.let { original -> scope.launch { repository.deleteSearchKeyword(original.keyword) } }
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }
    Spacer(Modifier.height(16.dp))
    SearchField(filter, { filter = it }, placeholder = "filter keywords", style = SearchFieldStyle.OUTLINED)
    Spacer(Modifier.height(8.dp))
    MetroText(
        "Type a keyword and a space before what you search for, such as “a headphones”.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
    Spacer(Modifier.height(8.dp))
    SearchKeywordList(settings, repository, apps, icons, onEdit = { editing = it }, filter = filter)
}
