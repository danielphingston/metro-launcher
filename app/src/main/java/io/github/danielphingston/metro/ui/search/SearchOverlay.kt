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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.danielphingston.metro.ui.start.BoundsTracker
import io.github.danielphingston.metro.ui.start.trackBounds
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
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
import io.github.danielphingston.metro.data.SearchKeywords
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.danielphingston.metro.data.KeywordMatch

/** The chip at the start of the search box while a search keyword is active. */
internal class KeywordChipState(val label: String, val icon: SearchIcon)

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

    /** The highlighted first row of a search keyword; Enter runs it. [replacesWeb] when it is the web search itself. */
    data class Keyword(val item: SearchItem, val replacesWeb: Boolean) : SearchRow {
        override val key get() = "keyword"
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
 * A search [keyword] puts its highlighted row first of all; the web row below still opens the
 * browser (unless the keyword is the web search itself, which is then that very row).
 */
internal fun searchRows(
    apps: List<AppInfo>,
    query: String,
    shortcuts: List<ShortcutHit>,
    engine: SearchEngine,
    now: Long,
    provided: List<ProvidedItems> = emptyList(),
    suggestions: Suggestions? = null,
    keyword: KeywordRow? = null,
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
        keyword?.let { add(SearchRow.Keyword(it.item, it.isWeb)) }
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
        if (keyword?.isWeb != true) {
            add(SearchRow.Caption("web"))
            add(SearchRow.Web(q, engine))
        }
        addProvided(Placement.BOTTOM)
    }
}

/**
 * Whether opening [row] is worth remembering as a recent search: the rows that act on the typed
 * text (an app, the web, an address, a keyword, the ask-assistant row), not a calculator copy, a
 * timer, a note or a contact, which would put numbers and names into the history.
 */
internal fun recordsSearch(row: SearchRow): Boolean = when (row) {
    is SearchRow.App, is SearchRow.Web, is SearchRow.OpenUrl, is SearchRow.Keyword -> true
    is SearchRow.Item -> row.provider == AskAssistantProvider.id
    else -> false
}

/**
 * What Search or Enter opens: the search keyword's row when one is active, else the action of a top provider (a timer; not a calculator answer, which only displays) when there is one,
 * else the first app when it is an exact or prefix match, else the address when the query is
 * one, else the web search. Null with nothing typed.
 */
internal fun topResult(rows: List<SearchRow>): SearchRow? =
    rows.firstOrNull { it is SearchRow.Keyword }
        ?: rows.firstOrNull { it is SearchRow.Item && it.placement == Placement.TOP && it.item.capturesEnter }
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
    // Metro Plus features: only the providers that are unlocked and switched on are asked at all.
    val unlocked by viewModel.quickTilesUnlocked.collectAsStateWithLifecycle()
    // A search keyword ("a cats") takes over the box as a chip; everything below then looks for the rest ("cats").
    val keywordMatch = SearchKeywords.detect(
        state.query, settings.searchKeywords, active = unlocked && settings.smartOn(SmartFeature.SEARCH_KEYWORDS),
    )
    val query = keywordMatch?.rest ?: state.query

    // Shortcuts come from a binder call, so they follow the typing after a short pause.
    val index = remember { ShortcutIndex.shared(context) }
    val found by produceState<Pair<String, List<ShortcutHit>>?>(null, query) {
        // The previous hits stay until the new ones arrive (see below), so the section does not blink while typing.
        if (query.isBlank()) { value = null; return@produceState }
        delay(SHORTCUT_DEBOUNCE_MILLIS)
        value = query to index.search(query)
    }
    // Hits of an earlier query stay only while the typing continues it; unrelated text drops them at once.
    val shortcuts = found?.takeIf { it.first == query || ProviderResults.continues(it.first, query) }?.second.orEmpty()
    val providers = remember(settings.smartFeatures, unlocked) { SearchProviders.active(settings, unlocked) }
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchInUses = history.searchInUses
    val searchContext = remember(context, apps, settings, searchInUses) {
        SearchContext(context, apps, settings, searchInUses = searchInUses)
    }
    val provided = rememberProviderResults(providers, query, searchContext)
    val suggestionsOn = unlocked && settings.smartOn(SmartFeature.SUGGESTIONS)
    val suggestions = remember(apps, history, suggestionsOn) {
        if (suggestionsOn) Suggestions(HistoryRules.mostUsed(apps, history), history.searches) else null
    }
    // Building the row asks the package manager and telephony, so it happens off the main thread. A row built
    // for an earlier keyword or text is ignored, like the shortcut hits above.
    val builtKeyword by produceState<Pair<KeywordMatch, KeywordRow?>?>(null, keywordMatch, searchContext) {
        val match = keywordMatch
        value = if (match == null) null else withContext(Dispatchers.Default) {
            match to KeywordRows.build(match, searchContext, RealSearchInPhone(context))
        }
    }
    val keywordRow = builtKeyword?.takeIf { it.first == keywordMatch }?.second
    val rows = remember(apps, query, shortcuts, settings.searchEngine, provided, suggestions, keywordRow) {
        searchRows(apps, query, shortcuts, settings.searchEngine, System.currentTimeMillis(), provided, suggestions, keywordRow)
    }
    val chip = keywordMatch?.let {
        KeywordChipState(KeywordRows.chipLabel(it.entry.target, apps, settings.assistant), KeywordRows.icon(it.entry.target, apps, settings.assistant))
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

    /** Runs [action]; the panel closes only when the launcher took the launch (a double tap is ignored and keeps it open). */
    fun perform(action: ItemAction, bounds: Rect?, onStarted: () -> Unit = {}) {
        when (action) {
            is ItemAction.Start -> if (launcher.start(action.intent, bounds, onStarted)) state.close()
            is ItemAction.Open -> {
                val accepted = launcher.open(bounds, onStarted) { screenBounds, options ->
                    action.run(ActionEnv(context, screenBounds, options, commands))
                }
                if (accepted) state.close()
            }
            is ItemAction.Run -> {
                state.close()
                action.run(ActionEnv(context, null, null, commands))
            }
        }
    }

    fun activate(row: SearchRow, bounds: Rect?) {
        // The history rows stay in the panel: one types the search again, the other wipes the history.
        if (row is SearchRow.RecentSearch) { state.queryCursor = null; state.query = row.text; return }
        if (row is SearchRow.ClearHistory) { viewModel.clearSearchHistory(); return }
        // What was typed is worth remembering once it led somewhere: only after the launch really started.
        val onStarted = { if (recordsSearch(row)) viewModel.recordSearch(query) }
        val accepted = when (row) {
            is SearchRow.App -> launcher.launch(row.app, bounds, onStarted)
            is SearchRow.Shortcut -> launcher.open(bounds) { screenBounds, options ->
                val content = TileContent.Shortcut(row.hit.packageName, row.hit.id, row.hit.userSerial, row.hit.shortLabel)
                ShortcutTiles.launch(context, content, screenBounds, options)
            }
            is SearchRow.OpenUrl -> launcher.open(bounds, onStarted) { screenBounds, options ->
                WebSearch.open(context, row.url, screenBounds, options)
            }
            is SearchRow.Web -> launcher.open(bounds, onStarted) { screenBounds, options ->
                WebSearch.open(context, row.url, screenBounds, options, inBrowser = true)
            }
            is SearchRow.Item -> { perform(row.item.action, bounds, onStarted); return }
            is SearchRow.Keyword -> { perform(row.item.action, bounds, onStarted); return }
            is SearchRow.Caption, is SearchRow.RecentSearch, SearchRow.ClearHistory -> return
        }
        if (accepted) state.close()
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
            onQueryChange = {
                state.queryCursor = null
                state.query = if (keywordMatch != null) SearchKeywords.withRest(keywordMatch, it) else it
            },
            chip = chip,
            onChipBackspace = {
                keywordMatch?.let {
                    // "yt cats" becomes "ytcats" with the cursor right after "yt", not somewhere inside it.
                    state.queryCursor = SearchKeywords.cursorAfterChipRemoval(it)
                    state.query = SearchKeywords.withoutChip(it)
                }
            },
            cursorAfterReset = state.queryCursor,
            onSubmit = { topResult(rows)?.let { activate(it, null) } },
            rows = rows,
            icons = viewModel.icons,
            monochrome = settings.monochromeIcons,
            plainIcons = settings.appListPlainIcons,
            focusRequester = focusRequester,
            onActivate = ::activate,
            onButton = { perform(it, null) },
            onChip = { chip, bounds ->
                // The chip acts on the typed text: count its use for the strip's order, and remember the search.
                perform(chip.action, bounds) {
                    viewModel.recordSearchInUse(chip.id)
                    viewModel.recordSearch(query)
                }
            },
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
    onChip: (ItemChip, Rect?) -> Unit = { _, _ -> },
    chip: KeywordChipState? = null,
    onChipBackspace: () -> Unit = {},
    cursorAfterReset: Int? = null,
) {
    val colors = Metro.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        SearchBox(
            query,
            onQueryChange,
            onSubmit,
            Modifier.swipeUpToClose(onDismiss).focusRequester(focusRequester).semantics { contentDescription = "search" },
            chip = chip?.let { { KeywordChip(it.label, it.icon, icons) } },
            onChipBackspace = onChipBackspace,
            cursorAfterReset = cursorAfterReset,
        )
        val list = rememberLazyListState()
        // Items are keyed, so a LazyColumn follows the item that was first: rows arriving above it (the
        // calculator's answer, a moment after the web row) would scroll themselves out of view, under the
        // search field. While the user has not scrolled, every change of rows stays at the top instead.
        var userScrolled by remember { mutableStateOf(false) }
        LaunchedEffect(query) { userScrolled = false }
        LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.collect { if (it) userScrolled = true } }
        SideEffect { if (!userScrolled) list.requestScrollToItem(0) }
        // Swiping up past the end of the results (or with nothing to scroll) closes the panel too.
        val swipeUp = rememberSwipeUpToClose(list, onDismiss)
        // The keyboard's inset already covers the navigation bar, so only one of the two pads the end of the list.
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val bottomInset = if (imeUp) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Taps on the gaps between results close the panel; the rows consume their own. The list is clipped to
        // this box, which starts under the search field, so no row is ever drawn behind the field.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .nestedScroll(swipeUp)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(top = 4.dp, bottom = bottomInset + 16.dp),
            ) {
                // Keys belong to the item, not the query, so a row that stays keeps its place and state.
                items(rows, key = { it.key }, contentType = { it::class }) { row ->
                    if (row is SearchRow.Item && row.item.chips.isNotEmpty()) {
                        ChipStrip(row.item, icons, monochrome, plainIcons, onChip)
                        return@items
                    }
                    Box(Modifier.padding(start = ListStart, end = 12.dp)) {
                        ResultRowFor(row, icons, monochrome, plainIcons, onActivate, onLongClick, onButton)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRowFor(
    row: SearchRow,
    icons: IconCache,
    monochrome: Boolean,
    plainIcons: Boolean,
    onActivate: (SearchRow, Rect?) -> Unit,
    onLongClick: (AppInfo, Rect) -> Unit,
    onButton: (ItemAction) -> Unit,
) {
    val colors = Metro.colors
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
        is SearchRow.Keyword -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(colors.accent.copy(alpha = 0.2f))
                .drawBehind { drawRect(colors.accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
                .padding(start = 12.dp, end = 4.dp),
        ) { ItemRow(row.item, icons, monochrome, plainIcons, { onActivate(row, it) }, onButton) }
    }
}

/**
 * "search “query” in" and the apps that can search it, as a strip of icon chips that scrolls sideways
 * (search in apps). It runs to both screen edges so a chip scrolls off the screen, not off an invisible margin;
 * the chips use the app list's icons, so they follow the plain / tiled setting.
 */
@Composable
private fun ChipStrip(item: SearchItem, icons: IconCache, monochrome: Boolean, plainIcons: Boolean, onChip: (ItemChip, Rect?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        MetroText(
            item.title,
            MetroType.caption,
            color = Metro.colors.subtle,
            maxLines = 1,
            modifier = Modifier.padding(start = ListStart, end = 12.dp, top = 16.dp, bottom = 2.dp).semantics { heading() },
        )
        LazyRow(
            contentPadding = PaddingValues(start = ListStart - ChipPad, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(item.chips, key = { it.id }) { chip -> StripChip(chip, icons, monochrome, plainIcons, onChip) }
        }
    }
}

private val ChipWidth = 72.dp
private val ChipPad = 4.dp

/** One chip: the icon over a one-line name; the whole 72dp-wide column is the touch target. */
@Composable
private fun StripChip(chip: ItemChip, icons: IconCache, monochrome: Boolean, plainIcons: Boolean, onChip: (ItemChip, Rect?) -> Unit) {
    val tracker = remember { BoundsTracker() }
    Column(
        Modifier
            .width(ChipWidth)
            .trackBounds(tracker)
            .metroTilt()
            .clickable(role = Role.Button) { onChip(chip, tracker.bounds()) }
            .padding(horizontal = ChipPad, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = chip.description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The description above says it all; the picture and the label are not read again.
        Box(Modifier.clearAndSetSemantics { }) { IconView(chip.icon, icons, monochrome, plainIcons) }
        Spacer(Modifier.height(4.dp))
        MetroText(
            chip.label,
            MetroType.caption.copy(textAlign = TextAlign.Center),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
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
                RoundButton(button.label, { onButton(button.action) }) { ButtonIcon(button.icon, icons, monochrome, plainIcons) }
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

/**
 * The picture inside a round button: a hairline glyph, or an installed app's own icon (WhatsApp's, read
 * from the phone) at a size that sits inside the ring like the glyphs do.
 */
@Composable
private fun ButtonIcon(icon: SearchIcon, icons: IconCache, monochrome: Boolean, plainIcons: Boolean) {
    if (icon is SearchIcon.App) KeywordIcon(icon, icons, 28.dp, Metro.colors.foreground) else IconView(icon, icons, monochrome, plainIcons, boxed = false)
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
