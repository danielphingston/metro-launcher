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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.IntOffset
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
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroText
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

private val ListStart = 20.dp
private val IconBox = 44.dp

@Composable
fun AppListScreen(viewModel: LauncherViewModel, state: AppListState, launcher: AppLauncher) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val colors = Metro.colors

    val rows = remember(apps, state.query) { appListRows(apps, state.query, System.currentTimeMillis()) }
    val letters = remember(rows) { rows.mapNotNullTo(mutableSetOf()) { (it as? AppListRow.Header)?.group } }

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
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SearchBox(query, onQueryChange, onSubmit)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ListStart,
                end = 12.dp,
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
                        onClick = { bounds -> onClick(row.app, bounds) },
                        onLongClick = { bounds -> onLongClick(row.app, bounds) },
                    )
                }
            }
        }
    }
}

/** A slim box with a magnifier; the border turns accent while typing. The search overlay reuses it. */
@Composable
internal fun SearchBox(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Metro.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MetroType.listItem.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier
            .fillMaxWidth()
            .turnstile(LocalTurnstile.current, "search")
            .padding(start = ListStart, end = 12.dp, top = 12.dp, bottom = 8.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(colors.control.copy(alpha = 0.6f))
                    .then(if (focused) Modifier.border(2.dp, colors.accent) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) MetroText("search", MetroType.listItem, color = colors.subtle, maxLines = 1)
                    field()
                }
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .size(18.dp)
                        .drawBehind { drawMagnifier(if (focused) colors.accent else colors.subtle) },
                )
            }
        },
    )
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMagnifier(color: Color) {
    val stroke = 1.5.dp.toPx()
    val r = size.width * 0.32f
    val c = Offset(size.width * 0.42f, size.height * 0.42f)
    drawCircle(color, r, c, style = Stroke(stroke))
    val from = c + Offset(r, r) * 0.72f
    drawLine(color, from, Offset(size.width * 0.92f, size.height * 0.92f), stroke, StrokeCap.Round)
}

/** The letter that starts each group: an accent outline square that opens the jump grid. */
@Composable
private fun LetterTile(group: String, onClick: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .turnstile(LocalTurnstile.current, group)
            .padding(top = 12.dp, bottom = 6.dp)
            .size(IconBox)
            .metroTilt()
            .border(2.dp, colors.accent)
            .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "jump to letter", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(group, MetroType.style(24.sp, FontWeight.Light), color = colors.accent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    icons: IconCache,
    monochrome: Boolean,
    onClick: (Rect?) -> Unit,
    onLongClick: (Rect) -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .turnstile(LocalTurnstile.current, app.key) { bounds }
            .metroTilt(maxDegrees = 6f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(bounds) },
                onLongClick = { bounds?.let(onLongClick) },
                onLongClickLabel = "more options",
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(IconBox).background(Metro.colors.accent), contentAlignment = Alignment.Center) {
            val px = with(LocalDensity.current) { 28.dp.roundToPx() }
            val cached = icons.peek(app, px, monochrome)
            val icon by produceState<TileIcon?>(cached, app.key, monochrome, icons.generation) {
                value = icons.peek(app, px, monochrome) ?: withContext(Dispatchers.Default) { icons.load(app, px, monochrome) }
            }
            icon?.let { Image(it.bitmap, contentDescription = null, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        MetroText(app.label, MetroType.listItem, maxLines = 1)
    }
}

/** Windows Phone's letter grid: tap a letter to jump; letters with no apps are dimmed. */
@Composable
private fun JumpList(available: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Metro.colors
    MetroFlyout(onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background.copy(alpha = 0.95f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppGroups.ALL.chunked(4).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.forEach { group ->
                        val enabled = group in available
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(if (enabled) colors.accent else colors.chrome)
                                .then(
                                    if (enabled) {
                                        Modifier.metroTilt().clickable(onClickLabel = "jump to $group") { onPick(group) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            MetroText(
                                group,
                                MetroType.style(30.sp, FontWeight.Light),
                                color = if (enabled) colors.onAccent else colors.subtle.copy(alpha = 0.6f),
                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 4.dp),
                            )
                        }
                    }
                    repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
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
            MetroMenu(
                items = items,
                modifier = Modifier
                    .offset { IntOffset(0, y.roundToInt()) }
                    .onGloballyPositioned { menuHeight = it.size.height },
            )
        }
    }
}
