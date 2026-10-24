package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.MediaSource
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.tiles.FolderTile
import io.github.danielphingston.metro.ui.tiles.PeopleTile
import io.github.danielphingston.metro.ui.tiles.PeopleTiles
import io.github.danielphingston.metro.ui.tiles.PhotosTile
import io.github.danielphingston.metro.ui.tiles.PhotosTiles
import io.github.danielphingston.metro.ui.tiles.ShortcutTile
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import io.github.danielphingston.metro.ui.tiles.WeatherTile
import io.github.danielphingston.metro.ui.tiles.WeatherTiles
import io.github.danielphingston.metro.ui.tiles.WidgetPicker
import io.github.danielphingston.metro.ui.tiles.WidgetTile
import io.github.danielphingston.metro.live.CalendarSource
import io.github.danielphingston.metro.live.ClockSource
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt
import kotlin.random.Random

@Stable
class StartState(val scroll: ScrollState) {
    var editing by mutableStateOf(false)
        private set
    var selectedId by mutableStateOf<Long?>(null)
        private set

    /** Tile being dragged and its top-left corner in grid coordinates (px). */
    var dragId by mutableStateOf<Long?>(null)
        internal set
    var dragTopLeft by mutableStateOf(Offset.Zero)
        internal set

    fun select(id: Long) {
        editing = true
        selectedId = id
    }

    /** Folder currently expanded on the start screen, if any. */
    var openFolderId by mutableStateOf<Long?>(null)

    /** The "add a tile" menu and the widget picker it can open. */
    var addMenuOpen by mutableStateOf(false)
    var widgetPickerOpen by mutableStateOf(false)

    fun exitEdit() {
        editing = false
        selectedId = null
        dragId = null
    }

    /** Closes transient UI; returns true if anything was open. */
    fun dismissOverlays(): Boolean {
        val any = addMenuOpen || widgetPickerOpen || openFolderId != null
        addMenuOpen = false
        widgetPickerOpen = false
        openFolderId = null
        return any
    }
}

@Composable
fun rememberStartState(): StartState {
    val scroll = rememberScrollState()
    return remember(scroll) { StartState(scroll) }
}

private val SidePadding = 12.dp
private val TileGap = 8.dp

@Composable
fun StartScreen(
    viewModel: LauncherViewModel,
    state: StartState,
    isCurrentPage: Boolean,
    launcher: AppLauncher,
    onShowApps: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadedTiles by viewModel.tiles.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val calendarGranted by viewModel.calendarGranted.collectAsStateWithLifecycle()
    val tiles = loadedTiles.orEmpty()

    // Live sources run only when a tile needs them and only while the launcher is visible
    // (collectAsStateWithLifecycle stops collecting below STARTED).
    val appPackages = tiles.mapNotNull { it.spec.appKey?.packageName }
    val needsClock = appPackages.any { it == defaults.clock || it == defaults.calendar }
    val needsCalendar = calendarGranted && defaults.calendar in appPackages
    val clock by remember(needsClock) {
        if (needsClock) ClockSource.minutes(context) else flowOf(null)
    }.collectAsStateWithLifecycle(null as ClockState?)
    val events by remember(needsCalendar) {
        if (needsCalendar) CalendarSource.upcoming(context) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val nowPlaying by remember { MediaSource.nowPlaying(context) }.collectAsStateWithLifecycle(emptyMap())
    val is24Hour = remember(clock) { android.text.format.DateFormat.is24HourFormat(context) }

    fun liveFor(tile: ResolvedTile): LiveContent? {
        val key = tile.spec.appKey ?: return null
        val pkg = key.packageName
        nowPlaying[key.notificationKey]?.let { return LiveContent.NowPlaying(it) }
        return when {
            pkg == defaults.clock -> LiveContent.Clock(clock ?: ClockState(System.currentTimeMillis(), null), is24Hour)
            pkg == defaults.calendar -> LiveContent.Calendar(clock?.now ?: System.currentTimeMillis(), events, is24Hour)
            else -> notifications[key.notificationKey]?.let {
                LiveContent.Notifications(it, settings.showNotificationContent)
            }
        }
    }

    val columns = settings.columns
    val placements = remember(tiles.map { it.spec.size }, columns) {
        TileGrid.pack(tiles.map { it.spec.size }, columns)
    }
    val flipped = remember { mutableStateMapOf<Long, Boolean>() }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gridWidth = maxWidth - SidePadding * 2
        val cell: Dp = (gridWidth + TileGap) / columns
        val cellPx = with(density) { cell.toPx() }
        val gapPx = with(density) { TileGap.toPx() }
        val viewportPx = with(density) { maxHeight.toPx() }
        val topInsetPx = WindowInsets.statusBars.getTop(density) + with(density) { 28.dp.toPx() }
        val rows = TileGrid.rows(placements)

        // Which live tiles are on screen: only those may flip.
        val liveTiles by rememberUpdatedState(
            tiles.zip(placements).mapNotNull { (tile, placement) ->
                val flips = when (tile.spec.content) {
                    TileContent.Weather, TileContent.Photos, TileContent.People -> tile.spec.size != TileSize.SMALL
                    else -> liveFor(tile)?.hasBack(tile.spec.size) == true
                }
                if (flips) tile.spec.id to placement else null
            },
        )
        val visibleLiveIds by remember {
            derivedStateOf {
                val top = state.scroll.value - topInsetPx
                liveTiles.filter { (_, p) ->
                    p.y * cellPx < top + viewportPx && (p.y + p.height) * cellPx > top
                }.map { it.first }
            }
        }
        val canFlip by rememberUpdatedState(isCurrentPage && !state.editing)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            // One ticker flips a random visible live tile every few seconds, like Windows Phone.
            // It is suspended entirely when the launcher is not in the foreground.
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    snapshotFlow { canFlip && visibleLiveIds.isNotEmpty() }.first { it }
                    delay(Random.nextLong(2_500, 5_500))
                    val ids = visibleLiveIds
                    if (!canFlip || ids.isEmpty()) continue
                    val id = ids.random()
                    flipped[id] = flipped[id] != true
                }
            }
        }

        // Auto-scroll while dragging a tile near the top or bottom edge.
        LaunchedEffect(state.dragId) {
            if (state.dragId == null) return@LaunchedEffect
            val edge = with(density) { 72.dp.toPx() }
            val speed = with(density) { 6.dp.toPx() }
            while (state.dragId != null) {
                withFrameNanos { }
                val tileCenterY = state.dragTopLeft.y + topInsetPx - state.scroll.value + cellPx / 2
                val delta = when {
                    tileCenterY < edge -> -speed
                    tileCenterY > viewportPx - edge -> speed
                    else -> 0f
                }
                if (delta != 0f) {
                    val consumed = state.scroll.dispatchRawDelta(delta)
                    state.dragTopLeft += Offset(0f, consumed)
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(state.scroll)
                .pointerInput(Unit) { detectTapGestures { if (state.editing) state.exitEdit() } },
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(28.dp))

            if (loadedTiles != null && tiles.isEmpty()) {
                MetroText(
                    "your start screen is empty.\nswipe left and press and hold an app to pin it.",
                    MetroType.body,
                    color = Metro.colors.subtle,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 48.dp),
                )
            }

            Box(
                Modifier
                    .padding(horizontal = SidePadding)
                    .size(width = gridWidth, height = cell * rows),
            ) {
                tiles.forEachIndexed { index, tile ->
                    val placement = placements[index]
                    androidx.compose.runtime.key(tile.spec.id) {
                        TileHost(
                            tile = tile,
                            placement = placement,
                            cellPx = cellPx,
                            gapPx = gapPx,
                            cell = cell,
                            state = state,
                            live = liveFor(tile),
                            flipped = flipped[tile.spec.id] == true && !state.editing,
                            monochromeIcons = settings.monochromeIcons,
                            viewModel = viewModel,
                            onLaunch = { bounds -> activateTile(context, tile, bounds, launcher, defaults, state) },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragMove = {
                                val center = state.dragTopLeft + Offset(
                                    placement.width * cellPx / 2,
                                    placement.height * cellPx / 2,
                                )
                                val cx = center.x / cellPx
                                val cy = center.y / cellPx
                                val target = tiles.indices.firstOrNull { i ->
                                    tiles[i].spec.id != tile.spec.id && placements[i].contains(cx, cy)
                                }
                                target?.let { viewModel.move(tile.spec.id, tiles[it].spec.id) }
                            },
                        )
                    }
                }
                val selectedIndex = tiles.indexOfFirst { it.spec.id == state.selectedId }
                if (state.editing && selectedIndex >= 0 && state.dragId == null) {
                    EditBadges(
                        placement = placements[selectedIndex],
                        cellPx = cellPx,
                        gapPx = gapPx,
                        onUnpin = {
                            viewModel.unpin(tiles[selectedIndex].spec.id)
                            state.exitEdit()
                        },
                        onResize = { viewModel.resize(tiles[selectedIndex].spec.id) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(SidePadding, 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AddTileButton(onClick = { state.addMenuOpen = true })
                AllAppsArrow(onShowApps)
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }

        if (state.addMenuOpen) {
            AddTileMenu(
                onAdd = { content, size ->
                    state.addMenuOpen = false
                    viewModel.addTile(content, size)
                },
                onWidgets = {
                    state.addMenuOpen = false
                    state.widgetPickerOpen = true
                },
                onDismiss = { state.addMenuOpen = false },
            )
        }
        if (state.widgetPickerOpen) {
            WidgetPicker(viewModel, onDismiss = { state.widgetPickerOpen = false })
        }
    }
}

@Composable
private fun TileHost(
    tile: ResolvedTile,
    placement: Placement,
    cellPx: Float,
    gapPx: Float,
    cell: Dp,
    state: StartState,
    live: LiveContent?,
    flipped: Boolean,
    monochromeIcons: Boolean,
    viewModel: LauncherViewModel,
    onLaunch: (Rect?) -> Unit,
    onLongPress: () -> Unit,
    onDragMove: () -> Unit,
) {
    val id = tile.spec.id
    val target = IntOffset((placement.x * cellPx).roundToInt(), (placement.y * cellPx).roundToInt())
    val position = remember { Animatable(target, IntOffset.VectorConverter) }
    val dragging = state.dragId == id
    LaunchedEffect(target, dragging) {
        if (dragging) {
            // Track the finger so the drop animation starts where the tile was released.
            snapshotFlow { state.dragTopLeft }.collect {
                position.snapTo(IntOffset(it.x.roundToInt(), it.y.roundToInt()))
            }
        } else {
            position.animateTo(target, tween(220))
        }
    }
    val selected = state.selectedId == id
    val scale by animateFloatAsState(
        when {
            dragging -> 1.06f
            state.editing && !selected -> 0.9f
            else -> 1f
        },
        label = "tileScale",
    )
    val alpha by animateFloatAsState(if (state.editing && !selected) 0.65f else 1f, label = "tileAlpha")
    var bounds by remember { mutableStateOf<Rect?>(null) }

    val currentPlacement by rememberUpdatedState(placement)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val widthDp = cell * placement.width - TileGap
    val heightDp = cell * placement.height - TileGap

    Box(
        Modifier
            .offset {
                if (dragging) {
                    IntOffset(state.dragTopLeft.x.roundToInt(), state.dragTopLeft.y.roundToInt())
                } else {
                    position.value
                }
            }
            .size(widthDp, heightDp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(tile.label)
                    (live as? LiveContent.Notifications)?.let { append(", ${it.data.count} new") }
                }
                onClick { onLaunch(bounds); true }
                onLongClick { state.select(id); true }
                customActions = listOf(
                    CustomAccessibilityAction("Unpin from start") { viewModel.unpin(id); true },
                    CustomAccessibilityAction("Resize") { viewModel.resize(id); true },
                )
            }
            .pointerInput(id) {
                // Long-press picks a tile up (entering edit mode); in edit mode the selected
                // tile can be dragged straight away.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val immediate = state.editing && state.selectedId == id
                    val start = if (immediate) {
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    } else {
                        awaitLongPressOrCancellation(down.id)?.also { onLongPress() }
                    } ?: return@awaitEachGesture
                    state.select(id)
                    state.dragTopLeft = Offset(
                        currentPlacement.x * cellPx,
                        currentPlacement.y * cellPx,
                    )
                    state.dragId = id
                    drag(start.id) { change ->
                        state.dragTopLeft += change.positionChange()
                        change.consume()
                        currentOnDragMove()
                    }
                    state.dragId = null
                }
            }
            .pointerInput(id) {
                detectTapGestures {
                    if (state.editing) {
                        if (state.selectedId != id) state.select(id)
                    } else {
                        onLaunch(bounds)
                    }
                }
            }
            .metroTilt(enabled = !state.editing),
    ) {
        TileBody(
            tile, live, flipped, monochromeIcons, viewModel.icons, cell, state.openFolderId == id,
            editing = state.editing,
            onWidgetLongPress = { onLongPress(); state.select(id) },
        )
    }
}

@Composable
private fun EditBadges(placement: Placement, cellPx: Float, gapPx: Float, onUnpin: () -> Unit, onResize: () -> Unit) {
    val density = LocalDensity.current
    val badge = 34.dp
    val badgePx = with(density) { badge.toPx() }
    val right = (placement.x + placement.width) * cellPx - gapPx
    val bottom = (placement.y + placement.height) * cellPx - gapPx
    val top = placement.y * cellPx
    val colors = Metro.colors
    // Unpin: top-right corner.
    Box(
        Modifier
            .offset { IntOffset((right - badgePx * 0.7f).roundToInt(), (top - badgePx * 0.3f).roundToInt()) }
            .size(badge)
            .background(colors.background, CircleShape)
            .border(2.dp, colors.foreground, CircleShape)
            .clickable(onClickLabel = "unpin", onClick = onUnpin)
            .drawBehind {
                val inset = size.minDimension * 0.32f
                val stroke = 2.dp.toPx()
                drawLine(colors.foreground, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
                drawLine(colors.foreground, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
            },
    )
    // Resize: bottom-right corner, arrow pointing to the next size.
    Box(
        Modifier
            .offset { IntOffset((right - badgePx * 0.7f).roundToInt(), (bottom - badgePx * 0.7f).roundToInt()) }
            .size(badge)
            .background(colors.background, CircleShape)
            .border(2.dp, colors.foreground, CircleShape)
            .clickable(onClickLabel = "resize", onClick = onResize)
            .drawBehind {
                val inset = size.minDimension * 0.3f
                val stroke = 2.dp.toPx()
                val a = Offset(inset, inset)
                val b = Offset(size.width - inset, size.height - inset)
                val head = size.minDimension * 0.18f
                drawLine(colors.foreground, a, b, stroke, StrokeCap.Round)
                drawLine(colors.foreground, b, b - Offset(head, 0f), stroke, StrokeCap.Round)
                drawLine(colors.foreground, b, b - Offset(0f, head), stroke, StrokeCap.Round)
            },
    )
}

/** Draws a tile according to its kind. */
@Composable
private fun TileBody(
    tile: ResolvedTile,
    live: LiveContent?,
    flipped: Boolean,
    monochromeIcons: Boolean,
    icons: IconCache,
    cell: Dp,
    folderOpen: Boolean,
    editing: Boolean,
    onWidgetLongPress: () -> Unit,
) {
    when (tile.spec.content) {
        is TileContent.App -> TileFace(tile.app!!, tile.spec.size, live, flipped, monochromeIcons, icons, cell)
        is TileContent.Shortcut -> ShortcutTile(tile, cell, icons, monochromeIcons)
        is TileContent.Widget -> WidgetTile(tile, cell, editing, onWidgetLongPress)
        TileContent.Weather -> WeatherTile(tile, cell, flipped)
        TileContent.Photos -> PhotosTile(tile, cell, flipped)
        TileContent.People -> PeopleTile(tile, cell, flipped)
        is TileContent.Folder -> FolderTile(tile, cell, icons, monochromeIcons, folderOpen)
    }
}

/** What tapping a tile does, by kind. */
private fun activateTile(
    context: android.content.Context,
    tile: ResolvedTile,
    bounds: Rect?,
    launcher: AppLauncher,
    defaults: DefaultApps,
    state: StartState,
) {
    when (val content = tile.spec.content) {
        is TileContent.App -> launcher.launch(tile.app!!, bounds)
        is TileContent.Shortcut -> {
            val (screenBounds, options) = launcher.transition(bounds)
            ShortcutTiles.launch(context, content, screenBounds, options)
        }
        is TileContent.Widget -> Unit // The hosted widget handles its own taps.
        TileContent.Weather -> WeatherTiles.intent(context)?.let { launcher.start(it, bounds) }
        TileContent.Photos -> PhotosTiles.intent(context, defaults)?.let { launcher.start(it, bounds) }
        TileContent.People -> PeopleTiles.intent(context)?.let { launcher.start(it, bounds) }
        is TileContent.Folder -> state.openFolderId = if (state.openFolderId == tile.spec.id) null else tile.spec.id
    }
}

/** Menu for tiles that don't come from the app list. */
@Composable
private fun AddTileMenu(
    onAdd: (TileContent, TileSize) -> Unit,
    onWidgets: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Metro.colors.background.copy(alpha = 0.6f))
            .clickable(onClickLabel = "close", onClick = onDismiss),
    ) {
        MetroMenu(
            items = listOf(
                "weather" to { onAdd(TileContent.Weather, TileSize.WIDE) },
                "photos" to { onAdd(TileContent.Photos, TileSize.WIDE) },
                "people" to { onAdd(TileContent.People, TileSize.MEDIUM) },
                "widgets" to onWidgets,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        )
    }
}

/** Circled plus that opens [AddTileMenu]. */
@Composable
private fun AddTileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = Metro.colors.foreground
    Box(
        modifier
            .size(44.dp)
            .metroTilt()
            .border(2.dp, color, CircleShape)
            .clickable(onClickLabel = "add a tile", onClick = onClick)
            .drawBehind {
                val stroke = 2.dp.toPx()
                val c = size.width / 2
                val arm = size.width * 0.2f
                drawLine(color, Offset(c - arm, c), Offset(c + arm, c), stroke, StrokeCap.Round)
                drawLine(color, Offset(c, c - arm), Offset(c, c + arm), stroke, StrokeCap.Round)
            },
    )
}

/** The circled arrow under the tiles that opens the app list. */
@Composable
private fun AllAppsArrow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = Metro.colors.foreground
    Box(
        modifier
            .size(44.dp)
            .metroTilt()
            .border(2.dp, color, CircleShape)
            .clickable(onClickLabel = "all apps", onClick = onClick)
            .drawBehind {
                val stroke = 2.dp.toPx()
                val y = size.height / 2
                val l = size.width * 0.3f
                val r = size.width * 0.7f
                val head = size.width * 0.16f
                drawLine(color, Offset(l, y), Offset(r, y), stroke, StrokeCap.Round)
                drawLine(color, Offset(r, y), Offset(r - head, y - head), stroke, StrokeCap.Round)
                drawLine(color, Offset(r, y), Offset(r - head, y + head), stroke, StrokeCap.Round)
            },
    )
}
