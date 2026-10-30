package io.github.danielphingston.metro.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.HistoryRules
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.applist.AppGroups
import io.github.danielphingston.metro.ui.applist.AppListIcon
import io.github.danielphingston.metro.ui.applist.SearchBox
import io.github.danielphingston.metro.ui.components.ClickableRow
import io.github.danielphingston.metro.ui.components.IconBox
import io.github.danielphingston.metro.ui.components.ListStart
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.drawMagnifier
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import kotlinx.coroutines.delay

/** A line in the search results. */
internal sealed interface SearchRow {
    val key: String

    /** A lowercase section caption: "apps", "shortcuts", "web". */
    data class Caption(val text: String) : SearchRow {
        override val key get() = "c:$text"
    }

    /** An app; [rank] is how well it matched ([SearchRanking.EXACT] is best). */
    data class App(val app: AppInfo, val rank: Int) : SearchRow {
        override val key get() = "a:${app.key.serialize()}"
    }

    /** An app shortcut; [app] is the app it belongs to, for the icon and the subtitle. */
    data class Shortcut(val hit: ShortcutHit, val app: AppInfo?) : SearchRow {
        override val key get() = "s:${hit.packageName}/${hit.id}#${hit.userSerial}"
    }

    /** The query read as an address; [shown] is what was typed. */
    data class OpenUrl(val url: String, val shown: String) : SearchRow {
        override val key get() = "url"
    }

    /** Search the web for [query]. */
    data class Web(val query: String, val engine: SearchEngine) : SearchRow {
        val url: String get() = engine.searchUrl(query)
        override val key get() = "web"
    }

    /** A search from the history; tapping it types it again. */
    data class RecentSearch(val text: String) : SearchRow {
        override val key get() = "q:$text"
    }

    /** Wipes the recent searches and launch counts. */
    data object ClearHistory : SearchRow {
        override val key get() = "clear-history"
    }

    /** A row made by a [SearchProvider]; [provider] is its id. */
    data class Item(val provider: String, val item: SearchItem, val placement: Placement) : SearchRow {
        override val key get() = "p:$provider:${item.key}"
    }
}

private const val MAX_APPS = 8

/** What the empty search offers (feature 5): the most used apps and the recent searches. */
internal data class Suggestions(val apps: List<AppInfo>, val searches: List<String>) {
    val isEmpty: Boolean get() = apps.isEmpty() && searches.isEmpty()
}

/**
 * The results for [query], top to bottom: the [Placement.TOP] rows of [provided] (calculator,
 * quick actions), an "open" row when it looks like an address, matching [apps] and [shortcuts]
 * under their captions, the [Placement.MIDDLE] provider rows, the web search, then the
 * [Placement.BOTTOM] provider rows (ask assistant). With nothing typed, the "recently added" apps.
 */
internal fun searchRows(
    apps: List<AppInfo>,
    query: String,
    shortcuts: List<ShortcutHit>,
    engine: SearchEngine,
    now: Long,
    provided: List<ProvidedItems> = emptyList(),
    suggestions: Suggestions? = null,
): List<SearchRow> {
    val q = query.trim()
    if (q.isEmpty() && suggestions != null && !suggestions.isEmpty) {
        return buildList {
            if (suggestions.apps.isNotEmpty()) {
                add(SearchRow.Caption("most used"))
                suggestions.apps.forEach { add(SearchRow.App(it, SearchRanking.CONTAINS)) }
            }
            if (suggestions.searches.isNotEmpty()) {
                add(SearchRow.Caption("recent searches"))
                suggestions.searches.forEach { add(SearchRow.RecentSearch(it)) }
                add(SearchRow.ClearHistory)
            }
        }
    }
    if (q.isEmpty()) {
        val recent = AppGroups.recentlyAdded(apps, now)
        return if (recent.isEmpty()) emptyList() else listOf(SearchRow.Caption("recently added")) +
            recent.map { SearchRow.App(it, SearchRanking.CONTAINS) }
    }
    return buildList {
        // A shortcut the shortcuts section lists is not listed again by a provider (WhatsApp chats).
        val listed = shortcuts.mapTo(HashSet()) { WhatsAppMatcher.shortcutKey(it.packageName, it.id, it.userSerial) }
        fun addProvided(placement: Placement) = provided.filter { it.provider.placement == placement }.forEach { group ->
            val items = group.items.filter { it.shortcutKey == null || it.shortcutKey !in listed }
            if (items.isEmpty()) return@forEach
            group.provider.section?.let { add(SearchRow.Caption(it)) }
            items.forEach { add(SearchRow.Item(group.provider.id, it, placement)) }
        }
        addProvided(Placement.TOP)
        if (WebSearch.looksLikeUrl(q)) add(SearchRow.OpenUrl(WebSearch.toUrl(q), q))
        val matches = SearchRanking.top(apps, q, MAX_APPS) { listOf(it.label) }
        if (matches.isNotEmpty()) {
            add(SearchRow.Caption("apps"))
            matches.forEach { add(SearchRow.App(it.item, it.rank)) }
        }
        if (shortcuts.isNotEmpty()) {
            add(SearchRow.Caption("shortcuts"))
            shortcuts.forEach { hit ->
                add(SearchRow.Shortcut(hit, apps.firstOrNull { it.key.packageName == hit.packageName && it.key.userSerial == hit.userSerial }))
            }
        }
        addProvided(Placement.MIDDLE)
        add(SearchRow.Caption("web"))
        add(SearchRow.Web(q, engine))
        addProvided(Placement.BOTTOM)
    }
}

/**
 * What Search or Enter opens: the answer of a top provider (a sum, a timer) when there is one,
 * else the first app when it is an exact or prefix match, else the address when the query is
 * one, else the web search. Null with nothing typed.
 */
internal fun topResult(rows: List<SearchRow>): SearchRow? =
    rows.firstOrNull { it is SearchRow.Item && it.placement == Placement.TOP }
        ?: rows.firstOrNull { it is SearchRow.App && it.rank <= SearchRanking.PREFIX }
        ?: rows.firstOrNull { it is SearchRow.OpenUrl }
        ?: rows.firstOrNull { it is SearchRow.Web }

/**
 * Windows 10 Mobile style search over the start screen: a panel that slides down with a search
 * field and results as you type. Composed (and so doing any work) only while it is open.
 */
@Composable
fun SearchOverlay(viewModel: LauncherViewModel, state: SearchState, launcher: AppLauncher, commands: LauncherCommands) {
    AnimatedVisibility(
        visible = state.isOpen,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(200)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 8 },
        exit = fadeOut(tween(120)),
    ) {
        SearchPanel(viewModel, state, launcher, commands)
    }
}

@Composable
private fun SearchPanel(viewModel: LauncherViewModel, state: SearchState, launcher: AppLauncher, commands: LauncherCommands) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = Metro.colors
    val query = state.query

    // Shortcuts come from a binder call, so they follow the typing after a short pause.
    val index = remember { ShortcutIndex.shared(context) }
    val found by produceState<Pair<String, List<ShortcutHit>>?>(null, query) {
        // Drop the previous query's hits at once, so no row is shown for text no longer typed.
        value = null
        if (query.isBlank()) return@produceState
        delay(SHORTCUT_DEBOUNCE_MILLIS)
        value = query to index.search(query)
    }
    // Hits of an earlier query are ignored even in the frame before the reset above.
    val shortcuts = found?.takeIf { it.first == query }?.second.orEmpty()
    // Metro Plus features: only the providers that are unlocked and switched on are asked at all.
    val unlocked by viewModel.quickTilesUnlocked.collectAsStateWithLifecycle()
    val providers = remember(settings.smartFeatures, unlocked) { SearchProviders.active(settings, unlocked) }
    val searchContext = remember(context, apps, settings) { SearchContext(context, apps, settings) }
    val provided = rememberProviderResults(providers, query, searchContext)
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val suggestionsOn = unlocked && settings.smartOn(SmartFeature.SUGGESTIONS)
    val suggestions = remember(apps, history, suggestionsOn) {
        if (suggestionsOn) Suggestions(HistoryRules.mostUsed(apps, history), history.searches) else null
    }
    val rows = remember(apps, query, shortcuts, settings.searchEngine, provided, suggestions) {
        searchRows(apps, query, shortcuts, settings.searchEngine, System.currentTimeMillis(), provided, suggestions)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    // The panel stays composed while it fades out; put the keyboard away at once.
    LaunchedEffect(state.isOpen) {
        if (!state.isOpen) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }

    fun perform(action: ItemAction, bounds: Rect?) {
        state.close()
        when (action) {
            is ItemAction.Start -> launcher.start(action.intent, bounds)
            is ItemAction.Open -> launcher.open(bounds) { screenBounds, options ->
                action.run(ActionEnv(context, screenBounds, options, commands))
            }
            is ItemAction.Run -> action.run(ActionEnv(context, null, null, commands))
        }
    }

    fun activate(row: SearchRow, bounds: Rect?) {
        // The history rows stay in the panel: one types the search again, the other wipes the history.
        if (row is SearchRow.RecentSearch) { state.query = row.text; return }
        if (row is SearchRow.ClearHistory) { viewModel.clearSearchHistory(); return }
        // What was typed is worth remembering once it led somewhere.
        if (row !is SearchRow.Caption) viewModel.recordSearch(query)
        state.close()
        when (row) {
            is SearchRow.App -> launcher.launch(row.app, bounds)
            is SearchRow.Shortcut -> launcher.open(bounds) { screenBounds, options ->
                val content = TileContent.Shortcut(row.hit.packageName, row.hit.id, row.hit.userSerial, row.hit.shortLabel)
                ShortcutTiles.launch(context, content, screenBounds, options)
            }
            is SearchRow.OpenUrl -> launcher.open(bounds) { screenBounds, options ->
                WebSearch.open(context, row.url, screenBounds, options)
            }
            is SearchRow.Web -> launcher.open(bounds) { screenBounds, options ->
                WebSearch.open(context, row.url, screenBounds, options, inBrowser = true)
            }
            is SearchRow.Item -> perform(row.item.action, bounds)
            is SearchRow.Caption, is SearchRow.RecentSearch, SearchRow.ClearHistory -> Unit
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = PANEL_ALPHA))
            // Nothing under the panel (tiles behind the status bar, say) may react to touches.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        SearchContent(
            query = query,
            onQueryChange = { state.query = it },
            onSubmit = { topResult(rows)?.let { activate(it, null) } },
            rows = rows,
            icons = viewModel.icons,
            monochrome = settings.monochromeIcons,
            plainIcons = settings.appListPlainIcons,
            focusRequester = focusRequester,
            onActivate = ::activate,
            onButton = { perform(it, null) },
            onLongClick = { app, _ ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                focus.clearFocus()
                state.menu = app
            },
            onDismiss = { state.close() },
        )

        state.menu?.let { app ->
            MetroFlyout(onDismiss = { state.menu = null }) {
                MetroMenu(
                    items = buildList {
                        if (!viewModel.isPinned(app)) add("pin to start" to { state.close(); viewModel.pin(app) })
                        add("app info" to { state.close(); viewModel.openAppInfo(app) })
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                )
            }
        }
    }
}

private const val SHORTCUT_DEBOUNCE_MILLIS = 150L
private const val PANEL_ALPHA = 0.96f

/** The search field and the results, without the animation, the shortcut query or the view model. */
@Composable
internal fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    rows: List<SearchRow>,
    icons: IconCache,
    monochrome: Boolean,
    plainIcons: Boolean,
    focusRequester: FocusRequester,
    onActivate: (SearchRow, Rect?) -> Unit,
    onLongClick: (AppInfo, Rect) -> Unit,
    onDismiss: () -> Unit,
    onButton: (ItemAction) -> Unit = {},
) {
    val colors = Metro.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        SearchBox(
            query,
            onQueryChange,
            onSubmit,
            Modifier.swipeUpToClose(onDismiss).focusRequester(focusRequester).semantics { contentDescription = "search" },
        )
        val list = rememberLazyListState()
        // Swiping up past the end of the results (or with nothing to scroll) closes the panel too.
        val swipeUp = rememberSwipeUpToClose(list, onDismiss)
        // Taps on the gaps between results close the panel; the rows consume their own.
        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(swipeUp).pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(
                    start = ListStart,
                    end = 12.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is SearchRow.Caption -> MetroText(
                            row.text,
                            MetroType.caption,
                            color = colors.subtle,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
                        )
                        is SearchRow.App -> ResultRow(
                            title = row.app.label,
                            subtitle = null,
                            onClick = { onActivate(row, it) },
                            onLongClick = { onLongClick(row.app, it) },
                        ) { AppListIcon(row.app, icons, monochrome, plainIcons) }
                        is SearchRow.Shortcut -> ResultRow(
                            title = row.hit.shortLabel,
                            subtitle = row.app?.label,
                            onClick = { onActivate(row, it) },
                        ) { row.app?.let { AppListIcon(it, icons, monochrome, plainIcons) } ?: Spacer(Modifier.size(IconBox)) }
                        is SearchRow.OpenUrl -> ResultRow(
                            title = "open ${row.shown}",
                            subtitle = null,
                            onClick = { onActivate(row, it) },
                        ) { Glyph { drawArrow(colors.foreground) } }
                        is SearchRow.Web -> ResultRow(
                            title = "search the web for “${row.query}”",
                            subtitle = row.engine.label,
                            onClick = { onActivate(row, it) },
                        ) { Glyph { drawMagnifier(colors.foreground) } }
                        is SearchRow.RecentSearch -> ResultRow(
                            title = row.text,
                            subtitle = null,
                            onClick = { onActivate(row, it) },
                        ) { Glyph { SearchGlyphs.history.draw(this, colors.foreground) } }
                        SearchRow.ClearHistory -> MetroText(
                            "clear history",
                            MetroType.body,
                            color = colors.accent,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .metroTilt()
                                .clickable(role = Role.Button) { onActivate(row, null) }
                                .padding(vertical = 8.dp),
                        )
                        is SearchRow.Item -> ItemRow(row.item, icons, monochrome, plainIcons, { onActivate(row, it) }, onButton)
                    }
                }
            }
        }
    }
}

/**
 * An icon, a title and an optional subtitle; tilts when pressed, and [onLongClick] opens a menu.
 * [trailing] adds content (buttons) after the text; [prominent] sets the title larger, for an answer.
 */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    onClick: (Rect?) -> Unit,
    onLongClick: ((Rect) -> Unit)? = null,
    prominent: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
    icon: @Composable () -> Unit,
) {
    ClickableRow(onClick = onClick, onLongClick = onLongClick) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            MetroText(title, if (prominent) MetroType.sectionTitle else MetroType.listItem, maxLines = 2)
            subtitle?.let { MetroText(it, MetroType.caption, color = Metro.colors.subtle, maxLines = 2) }
        }
        trailing()
    }
}

/** A provider's row: its icon, texts and round action buttons. */
@Composable
private fun ItemRow(
    item: SearchItem,
    icons: IconCache,
    monochrome: Boolean,
    plainIcons: Boolean,
    onClick: (Rect?) -> Unit,
    onButton: (ItemAction) -> Unit,
) {
    ResultRow(
        title = item.title,
        subtitle = item.subtitle,
        onClick = onClick,
        prominent = item.prominent,
        trailing = {
            item.buttons.forEach { button ->
                Spacer(Modifier.width(8.dp))
                RoundButton(button.label, { onButton(button.action) }) { IconView(button.icon, icons, monochrome, plainIcons, boxed = false) }
            }
        },
    ) { IconView(item.icon, icons, monochrome, plainIcons) }
}

/** The icon square of a provider row: an app icon, a hairline glyph on a control square, or a picture. */
@Composable
private fun IconView(icon: SearchIcon, icons: IconCache, monochrome: Boolean, plainIcons: Boolean, boxed: Boolean = true) {
    when (icon) {
        is SearchIcon.App -> AppListIcon(icon.app, icons, monochrome, plainIcons)
        is SearchIcon.Glyph -> {
            val color = Metro.colors.foreground
            if (boxed) Glyph { icon.draw(this, color) } else Box(Modifier.size(18.dp).drawBehind { icon.draw(this, color) })
        }
        is SearchIcon.Image -> Image(icon.bitmap, null, Modifier.size(IconBox), contentScale = ContentScale.Crop)
    }
}

/** A thin circle with an icon in it, for the actions on a row; [label] is what a screen reader says. */
@Composable
private fun RoundButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .metroTilt()
            .border(1.dp, Metro.colors.subtle, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** A hairline glyph, centred on a control-coloured square the size of an app icon. */
@Composable
private fun Glyph(draw: DrawScope.() -> Unit) {
    Box(Modifier.size(IconBox).background(Metro.colors.control), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).drawBehind(draw))
    }
}

/** A right-pointing arrow, the same hairline as the start screen's buttons. */
private fun DrawScope.drawArrow(color: Color) {
    val stroke = 1.5.dp.toPx()
    val y = size.height / 2
    val head = size.width * 0.3f
    val tip = Offset(size.width, y)
    drawLine(color, Offset(0f, y), tip, stroke, StrokeCap.Round)
    drawLine(color, tip, tip - Offset(head, head), stroke, StrokeCap.Round)
    drawLine(color, tip, tip - Offset(head, -head), stroke, StrokeCap.Round)
}
