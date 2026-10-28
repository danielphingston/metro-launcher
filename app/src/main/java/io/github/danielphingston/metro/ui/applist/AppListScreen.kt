package io.github.danielphingston.metro.ui.applist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileIcon
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.tiles.rememberAppShortcutItems
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.components.ClickableRow
import io.github.danielphingston.metro.ui.components.IconBox
import io.github.danielphingston.metro.ui.components.ListStart
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.SearchField
import io.github.danielphingston.metro.ui.components.drawMagnifier as drawSharedMagnifier
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.StatusBarScrim
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.start.LocalTurnstile
import io.github.danielphingston.metro.ui.start.turnstile
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Stable
class AppListState {
    val list = LazyListState()
    var query by mutableStateOf("")
    var jumpListOpen by mutableStateOf(false)
    var menu by mutableStateOf<Pair<AppInfo, Rect>?>(null)

    /** Whether the jump grid or an app's menu is open; the pager must not swipe away from it. */
    val overlayOpen: Boolean get() = jumpListOpen || menu != null

    fun reset() {
        query = ""
        jumpListOpen = false
        menu = null
    }

    /** Returns true if back was consumed. */
    fun handleBack(): Boolean {
        when {
            menu != null -> menu = null
            jumpListOpen -> jumpListOpen = false
            query.isNotEmpty() -> query = ""
            else -> return false
        }
        return true
    }
}

internal sealed interface AppListRow {
    val key: String

    data class Header(val group: String) : AppListRow {
        override val key get() = "h:$group"
    }

    /** The caption above the "recently added" apps. */
    data object RecentHeader : AppListRow {
        override val key get() = "recent"
    }

    /** An app in the list; [recent] marks its copy in the "recently added" group. */
    data class App(val app: AppInfo, val recent: Boolean = false) : AppListRow {
        override val key get() = "${if (recent) "r" else "a"}:${app.key.serialize()}"
    }
}

/**
 * The rows for [apps] (sorted by label): search results while [query] is set, otherwise
 * "recently added" followed by the alphabetical groups.
 */
internal fun appListRows(apps: List<AppInfo>, query: String, now: Long): List<AppListRow> {
    if (query.isNotBlank()) return apps.filter { AppGroups.matches(it.label, query) }.map { AppListRow.App(it) }
    val recent = AppGroups.recentlyAdded(apps, now)
    val grouped = apps.groupBy { AppGroups.groupOf(it.label) }.toSortedMap(compareBy { AppGroups.ALL.indexOf(it) })
    return buildList {
        if (recent.isNotEmpty()) {
            add(AppListRow.RecentHeader)
            recent.forEach { add(AppListRow.App(it, recent = true)) }
        }
        grouped.forEach { (group, members) ->
            add(AppListRow.Header(group))
            members.forEach { add(AppListRow.App(it)) }
        }
    }
}

private val ListEnd = 12.dp

/** The search box's height with its padding; the scrubber starts below it. */
private val SearchBoxHeight = 60.dp

/** The widest the list gets; on a tablet it stays this wide in the middle of the screen. */
private val ListMaxWidth = 560.dp

@Composable
fun AppListScreen(viewModel: LauncherViewModel, state: AppListState, launcher: AppLauncher) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val colors = Metro.colors

    val rows = remember(apps, state.query) { appListRows(apps, state.query, System.currentTimeMillis()) }
    val scrubLetters = remember(rows) { rows.mapNotNull { (it as? AppListRow.Header)?.group } }
    val letters = remember(scrubLetters) { scrubLetters.toSet() }

    LaunchedEffect(state.query) { if (state.query.isNotEmpty()) state.list.scrollToItem(0) }

    // Photo and wallpaper backgrounds belong to the start screen: the list sits on a nearly opaque
    // page so text stays readable.
    val translucent = LocalStartBackdrop.current.mode.let {
        it == StartBackground.PHOTO_FULL || it == StartBackground.SYSTEM_WALLPAPER
    }
    Box(Modifier.fillMaxSize().then(if (translucent) Modifier.background(colors.background.copy(alpha = 0.94f)) else Modifier)) {
        AppListContent(
            rows = rows,
            listState = state.list,
            icons = viewModel.icons,
            monochrome = settings.monochromeIcons,
            plainIcons = settings.appListPlainIcons,
            scrubberInset = if (scrubLetters.isEmpty()) 0.dp else ScrubberWidth,
            query = state.query,
            onQueryChange = { state.query = it },
            onSubmit = {
                (rows.firstOrNull { it is AppListRow.App } as? AppListRow.App)?.let { launcher.launch(it.app, null) }
                focus.clearFocus()
            },
            onLetterClick = {
                focus.clearFocus()
                state.jumpListOpen = true
            },
            onClick = { app, bounds -> launcher.launch(app, bounds) },
            onLongClick = { app, bounds ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                focus.clearFocus()
                state.menu = app to bounds
            },
        )
        // Search results have no letter groups to scrub through.
        if (state.query.isEmpty()) {
            AlphabetScrubber(
                letters = scrubLetters,
                highlight = settings.scrubberHighlight,
                highlightPosition = settings.scrubberHighlightPosition,
                positionPercent = settings.scrubberPosition,
                onScrub = { group ->
                    val index = rows.indexOfFirst { it is AppListRow.Header && it.group == group }
                    if (index >= 0) scope.launch { state.list.scrollToItem(index) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .statusBarsPadding()
                    .padding(top = SearchBoxHeight, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            )
        }
        // The list sits on a page colour (nearly opaque in the photo modes), so the scrim is the page colour too.
        StatusBarScrim(overPhoto = false)

        if (state.jumpListOpen) {
            JumpList(
                available = letters,
                onPick = { group ->
                    state.jumpListOpen = false
                    val index = rows.indexOfFirst { it is AppListRow.Header && it.group == group }
                    if (index >= 0) scope.launch { state.list.scrollToItem(index) }
                },
                onDismiss = { state.jumpListOpen = false },
            )
        }

        state.menu?.let { (app, bounds) ->
            AppMenu(
                app = app,
                anchor = bounds,
                pinned = viewModel.isPinned(app),
                onPin = { state.menu = null; viewModel.pin(app) },
                onUninstall = { state.menu = null; viewModel.uninstall(app) },
                onAppInfo = { state.menu = null; viewModel.openAppInfo(app) },
                onPinShortcut = { state.menu = null; viewModel.addTile(it, TileSize.MEDIUM) },
                onDismiss = { state.menu = null },
            )
        }
    }
}

/** The search box and the list, without the overlays or the view model. */
@Composable
internal fun AppListContent(
    rows: List<AppListRow>,
    listState: LazyListState,
    icons: IconCache,
    monochrome: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLetterClick: () -> Unit,
    onClick: (AppInfo, Rect?) -> Unit,
    onLongClick: (AppInfo, Rect) -> Unit,
    plainIcons: Boolean = false,
    /** Room kept free at the end edge for the alphabet scrubber. */
    scrubberInset: Dp = 0.dp,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        // Centred on a wide screen. The margins are padding of the full-width list, so it still
        // scrolls from anywhere on the screen.
        val margin = ((maxWidth - ListMaxWidth) / 2).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SearchBox(query, onQueryChange, onSubmit, Modifier.padding(horizontal = margin))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = margin + ListStart,
                    end = margin + maxOf(ListEnd, scrubberInset - margin),
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is AppListRow.Header -> LetterTile(row.group, onClick = onLetterClick)
                        AppListRow.RecentHeader -> MetroText(
                            "recently added",
                            MetroType.caption,
                            color = Metro.colors.subtle,
                            modifier = Modifier.turnstile(LocalTurnstile.current, "recent").padding(top = 12.dp, bottom = 6.dp),
                        )
                        is AppListRow.App -> AppRow(
                            app = row.app,
                            icons = icons,
                            monochrome = monochrome,
                            plainIcons = plainIcons,
                            onClick = { bounds -> onClick(row.app, bounds) },
                            onLongClick = { bounds -> onLongClick(row.app, bounds) },
                        )
                    }
                }
            }
        }
    }
}

/** The app list's search field; the search overlay reuses it. */
@Composable
internal fun SearchBox(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    SearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = "search",
        modifier = modifier.turnstile(LocalTurnstile.current, "search"),
        padding = PaddingValues(start = ListStart, end = ListEnd, top = 12.dp, bottom = 8.dp),
        onSearch = onSubmit,
    )
}

/** Kept here for the start screen's search button; the drawing lives with [SearchField]. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMagnifier(color: Color) = drawSharedMagnifier(color)

/** The letter that starts each group: an accent outline square that opens the jump grid. */
@Composable
private fun LetterTile(group: String, onClick: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .turnstile(LocalTurnstile.current, group)
            .padding(top = 12.dp, bottom = 6.dp)
            // Grows with the font scale instead of clipping the letter.
            .defaultMinSize(IconBox, IconBox)
            .metroTilt()
            .border(2.dp, colors.accent)
            .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "jump to letter", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(group, MetroType.style(24.sp, FontWeight.Light), color = colors.accent)
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    icons: IconCache,
    monochrome: Boolean,
    plainIcons: Boolean,
    onClick: (Rect?) -> Unit,
    onLongClick: (Rect) -> Unit,
) {
    val turnstile = LocalTurnstile.current
    ClickableRow(
        onClick = onClick,
        onLongClick = onLongClick,
        beforeTilt = { bounds -> Modifier.turnstile(turnstile, app.key, bounds) },
    ) {
        AppListIcon(app, icons, monochrome, plainIcons)
        Spacer(Modifier.width(14.dp))
        MetroText(app.label, MetroType.listItem, maxLines = 1)
    }
}

/**
 * An app's icon as the app list shows it: on an accent square, or with [plainIcons] bare and
 * larger. The search results use it too, so both follow the same settings.
 */
@Composable
internal fun AppListIcon(app: AppInfo, icons: IconCache, monochrome: Boolean, plainIcons: Boolean) {
    // The themed (white) icons are made for the accent square; bare on the page they would vanish.
    val themed = monochrome && !plainIcons
    val iconSize = if (plainIcons) IconBox else 28.dp
    Box(
        Modifier.size(IconBox).then(if (plainIcons) Modifier else Modifier.background(Metro.colors.accent)),
        contentAlignment = Alignment.Center,
    ) {
        val px = with(LocalDensity.current) { iconSize.roundToPx() }
        val cached = icons.peek(app, px, themed)
        val icon by produceState<TileIcon?>(cached, app.key, themed, px, icons.generation) {
            value = icons.peek(app, px, themed) ?: withContext(Dispatchers.Default) { icons.load(app, px, themed) }
        }
        icon?.let { Image(it.bitmap, contentDescription = null, modifier = Modifier.size(iconSize)) }
    }
}

/** Windows Phone's letter grid: tap a letter to jump; letters with no apps are dimmed. */
@Composable
private fun JumpList(available: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Metro.colors
    val lines = AppGroups.ALL.chunked(4)
    val gap = 6.dp
    MetroFlyout(onDismiss) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.background.copy(alpha = 0.95f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Four letters a row, as wide as the screen allows; on a wide, short screen (a tablet
            // in landscape) smaller, so every row fits.
            val square = minOf((maxWidth - gap * 3) / 4, (maxHeight - gap * (lines.size - 1)) / lines.size)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                lines.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { group ->
                            val enabled = group in available
                            JumpTile(group, enabled, Modifier.size(square)) { onPick(group) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpTile(group: String, enabled: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val colors = Metro.colors
    Box(
        modifier
            .background(if (enabled) colors.accent else colors.chrome)
            .then(if (enabled) Modifier.metroTilt().clickable(onClickLabel = "jump to $group", onClick = onPick) else Modifier),
    ) {
        MetroText(
            group,
            MetroType.style(30.sp, FontWeight.Light),
            color = if (enabled) colors.onSolidAccent else colors.subtle.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun AppMenu(
    app: AppInfo,
    anchor: Rect,
    pinned: Boolean,
    onPin: () -> Unit,
    onUninstall: () -> Unit,
    onAppInfo: () -> Unit,
    onPinShortcut: (TileContent.Shortcut) -> Unit,
    onDismiss: () -> Unit,
) {
    val shortcutItems = rememberAppShortcutItems(app, onPin = onPinShortcut)
    val items = buildList {
        if (!pinned) add("pin to start" to onPin)
        addAll(shortcutItems)
        if (app.isUninstallable) add("uninstall" to onUninstall)
        add("app info" to onAppInfo)
    }
    var menuHeight by remember { mutableIntStateOf(0) }
    MetroFlyout(onDismiss) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Open below the pressed row, or above it when there is no room.
            val screenHeight = constraints.maxHeight
            val y = if (anchor.bottom + menuHeight > screenHeight) anchor.top - menuHeight else anchor.bottom
            // As wide as the list, from its left edge: the whole screen on a phone, the centred
            // list on a tablet.
            val density = LocalDensity.current
            val start = with(density) { ListStart.toPx() }
            val width = with(density) { (anchor.width + start + ListEnd.toPx()).toDp() }
            MetroMenu(
                items = items,
                modifier = Modifier
                    .offset { IntOffset((anchor.left - start).roundToInt(), y.roundToInt()) }
                    .width(width)
                    .onGloballyPositioned { menuHeight = it.size.height },
            )
        }
    }
}
