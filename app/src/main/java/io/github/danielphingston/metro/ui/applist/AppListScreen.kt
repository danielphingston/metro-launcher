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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileIcon
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
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

private sealed interface Row {
    val key: String

    data class Header(val group: String) : Row {
        override val key get() = "h:$group"
    }

    data class App(val app: AppInfo) : Row {
        override val key get() = "a:${app.key.serialize()}"
    }
}

@Composable
fun AppListScreen(viewModel: LauncherViewModel, state: AppListState, launcher: AppLauncher) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current

    val grouped = remember(apps) {
        apps.groupBy { AppGroups.groupOf(it.label) }
            .toSortedMap(compareBy { AppGroups.ALL.indexOf(it) })
    }
    val rows: List<Row> = remember(grouped, state.query) {
        if (state.query.isNotBlank()) {
            apps.filter { AppGroups.matches(it.label, state.query) }.map(Row::App)
        } else {
            grouped.flatMap { (group, members) -> listOf(Row.Header(group)) + members.map(Row::App) }
        }
    }

    LaunchedEffect(state.query) { if (state.query.isNotEmpty()) state.list.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SearchBox(
                query = state.query,
                onQueryChange = { state.query = it },
                onSubmit = {
                    (rows.firstOrNull() as? Row.App)?.let { launcher.launch(it.app, null) }
                    focus.clearFocus()
                },
            )
            LazyColumn(
                state = state.list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 12.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is Row.Header -> LetterTile(row.group, onClick = {
                            focus.clearFocus()
                            state.jumpListOpen = true
                        })
                        is Row.App -> AppRow(
                            app = row.app,
                            icons = viewModel.icons,
                            monochrome = settings.monochromeIcons,
                            onClick = { bounds -> launcher.launch(row.app, bounds) },
                            onLongClick = { bounds ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                focus.clearFocus()
                                state.menu = row.app to bounds
                            },
                        )
                    }
                }
            }
        }

        if (state.jumpListOpen) {
            JumpList(
                available = grouped.keys,
                onPick = { group ->
                    state.jumpListOpen = false
                    val index = rows.indexOfFirst { it is Row.Header && it.group == group }
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
                onDismiss = { state.menu = null },
            )
        }
    }
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit) {
    val colors = Metro.colors
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MetroType.body.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, if (query.isEmpty()) colors.subtle else colors.accent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) MetroText("search", MetroType.body, color = colors.subtle)
                field()
            }
        },
    )
}

@Composable
private fun LetterTile(group: String, onClick: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .padding(vertical = 8.dp)
            .size(52.dp)
            .metroTilt()
            .border(2.dp, colors.accent)
            .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "jump to letter", onClick = onClick),
    ) {
        MetroText(
            group,
            MetroType.style(MetroType.sectionTitle.fontSize),
            color = colors.accent,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp),
        )
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
            .metroTilt(maxDegrees = 6f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(bounds) },
                onLongClick = { bounds?.let(onLongClick) },
                onLongClickLabel = "more options",
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).background(Metro.colors.accent), contentAlignment = Alignment.Center) {
            val px = with(LocalDensity.current) { 34.dp.roundToPx() }
            val cached = icons.peek(app, px, monochrome)
            val icon by produceState<TileIcon?>(cached, app.key, monochrome) {
                if (value == null) value = withContext(Dispatchers.Default) { icons.load(app, px, monochrome) }
            }
            icon?.let { Image(it.bitmap, contentDescription = null, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        MetroText(app.label, MetroType.body, maxLines = 1)
    }
}

/** Windows Phone's letter grid: tap a letter to jump; letters with no apps are dimmed. */
@Composable
private fun JumpList(available: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.92f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppGroups.ALL.chunked(4).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { group ->
                        val enabled = group in available
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(if (enabled) colors.accent else colors.chrome)
                                .then(if (enabled) Modifier.metroTilt().clickable { onPick(group) } else Modifier),
                        ) {
                            MetroText(
                                group,
                                MetroType.style(MetroType.pageTitle.fontSize * 0.6f),
                                color = if (enabled) colors.onAccent else colors.subtle,
                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp),
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
    onDismiss: () -> Unit,
) {
    val items = buildList {
        if (!pinned) add("pin to start" to onPin)
        if (app.isUninstallable) add("uninstall" to onUninstall)
        add("app info" to onAppInfo)
    }
    var menuHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Metro.colors.background.copy(alpha = 0.5f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
    ) {
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
