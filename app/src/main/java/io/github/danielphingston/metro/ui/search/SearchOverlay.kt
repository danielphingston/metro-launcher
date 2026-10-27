package io.github.danielphingston.metro.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.applist.AppGroups
import io.github.danielphingston.metro.ui.applist.SearchBox
import io.github.danielphingston.metro.ui.applist.drawMagnifier
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.start.AppIcon
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
}

private const val MAX_APPS = 8

/**
 * The results for [query]: an "open" row when it looks like an address, then matching [apps]
 * and [shortcuts] under their captions, then the web search. With nothing typed, the
 * "recently added" apps.
 */
internal fun searchRows(
    apps: List<AppInfo>,
    query: String,
    shortcuts: List<ShortcutHit>,
    engine: SearchEngine,
    now: Long,
): List<SearchRow> {
    val q = query.trim()
    if (q.isEmpty()) {
        val recent = AppGroups.recentlyAdded(apps, now)
        return if (recent.isEmpty()) emptyList() else listOf(SearchRow.Caption("recently added")) +
            recent.map { SearchRow.App(it, SearchRanking.CONTAINS) }
    }
    return buildList {
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
        add(SearchRow.Caption("web"))
        add(SearchRow.Web(q, engine))
    }
}

/**
 * What Search or Enter opens: the first app when it is an exact or prefix match, else the
 * address when the query is one, else the web search. Null with nothing typed.
 */
internal fun topResult(rows: List<SearchRow>): SearchRow? =
    rows.firstOrNull { it is SearchRow.App && it.rank <= SearchRanking.PREFIX }
        ?: rows.firstOrNull { it is SearchRow.OpenUrl }
        ?: rows.firstOrNull { it is SearchRow.Web }

private val ListStart = 20.dp
private val IconBox = 44.dp

/**
 * Windows 10 Mobile style search over the start screen: a panel that slides down with a search
 * field and results as you type. Composed (and so doing any work) only while it is open.
 */
@Composable
fun SearchOverlay(viewModel: LauncherViewModel, state: SearchState, launcher: AppLauncher) {
    AnimatedVisibility(
        visible = state.isOpen,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(200)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 8 },
        exit = fadeOut(tween(120)),
    ) {
        SearchPanel(viewModel, state, launcher)
    }
}

@Composable
private fun SearchPanel(viewModel: LauncherViewModel, state: SearchState, launcher: AppLauncher) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = Metro.colors
    val query = state.query

    // Shortcuts come from a binder call, so they follow the typing after a short pause.
    val index = remember { ShortcutIndex(context) }
    val shortcuts by produceState(emptyList<ShortcutHit>(), query) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(SHORTCUT_DEBOUNCE_MILLIS)
        value = index.search(query)
    }
    val rows = remember(apps, query, shortcuts, settings.searchEngine) {
        searchRows(apps, query, shortcuts, settings.searchEngine, System.currentTimeMillis())
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

    fun activate(row: SearchRow, bounds: Rect?) {
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
            is SearchRow.Caption -> Unit
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
            focusRequester = focusRequester,
            onActivate = ::activate,
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
    focusRequester: FocusRequester,
    onActivate: (SearchRow, Rect?) -> Unit,
    onLongClick: (AppInfo, Rect) -> Unit,
    onDismiss: () -> Unit,
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
                            iconBackground = colors.accent,
                            onClick = { onActivate(row, it) },
                            onLongClick = { onLongClick(row.app, it) },
                        ) { AppIcon(row.app, icons, 28.dp, monochrome) }
                        is SearchRow.Shortcut -> ResultRow(
                            title = row.hit.shortLabel,
                            subtitle = row.app?.label,
                            iconBackground = colors.accent,
                            onClick = { onActivate(row, it) },
                        ) { row.app?.let { AppIcon(it, icons, 28.dp, monochrome) } }
                        is SearchRow.OpenUrl -> ResultRow(
                            title = "open ${row.shown}",
                            subtitle = null,
                            iconBackground = colors.control,
                            onClick = { onActivate(row, it) },
                        ) { Glyph { drawArrow(colors.foreground) } }
                        is SearchRow.Web -> ResultRow(
                            title = "search the web for “${row.query}”",
                            subtitle = row.engine.label,
                            iconBackground = colors.control,
                            onClick = { onActivate(row, it) },
                        ) { Glyph { drawMagnifier(colors.foreground) } }
                    }
                }
            }
        }
    }
}

/** An icon square, a title and an optional subtitle; tilts when pressed, and [onLongClick] opens a menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    iconBackground: Color,
    onClick: (Rect?) -> Unit,
    onLongClick: ((Rect) -> Unit)? = null,
    icon: @Composable BoxScope.() -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .metroTilt(maxDegrees = 6f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onClick(bounds) },
                onLongClick = onLongClick?.let { long -> { bounds?.let(long) } },
                onLongClickLabel = if (onLongClick != null) "more options" else null,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(IconBox).background(iconBackground), contentAlignment = Alignment.Center, content = icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            MetroText(title, MetroType.listItem, maxLines = 2)
            subtitle?.let { MetroText(it, MetroType.caption, color = Metro.colors.subtle, maxLines = 1) }
        }
    }
}

/** An 18dp square for a hairline glyph. */
@Composable
private fun Glyph(draw: DrawScope.() -> Unit) {
    Box(Modifier.size(18.dp).drawBehind(draw))
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
