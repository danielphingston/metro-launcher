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
import androidx.compose.foundation.layout.Box
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
import io.github.danielphingston.metro.data.TileSize
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

    fun exitEdit() {
        editing = false
        selectedId = null
        dragId = null
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
    val needsClock = tiles.any { it.app.key.packageName.let { p -> p == defaults.clock || p == defaults.calendar } }
    val needsCalendar = calendarGranted && tiles.any { it.app.key.packageName == defaults.calendar }
    val clock by remember(needsClock) {
        if (needsClock) ClockSource.minutes(context) else flowOf(null)
    }.collectAsStateWithLifecycle(null as ClockState?)
    val events by remember(needsCalendar) {
        if (needsCalendar) CalendarSource.upcoming(context) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val is24Hour = remember(clock) { android.text.format.DateFormat.is24HourFormat(context) }

    fun liveFor(tile: ResolvedTile): LiveContent? {
        val pkg = tile.app.key.packageName
        return when {
            pkg == defaults.clock -> LiveContent.Clock(clock ?: ClockState(System.currentTimeMillis(), null), is24Hour)
            pkg == defaults.calendar -> LiveContent.Calendar(clock?.now ?: System.currentTimeMillis(), events, is24Hour)
            else -> notifications[tile.app.key.notificationKey]?.let {
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
                val live = liveFor(tile) ?: return@mapNotNull null
                if (live.hasBack(tile.spec.size)) tile.spec.id to placement else null
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
                            onLaunch = { bounds -> launcher.launch(tile.app, bounds) },
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

            AllAppsArrow(onShowApps, Modifier.align(Alignment.End).padding(SidePadding, 20.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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
                    append(tile.app.label)
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
        TileFace(
            app = tile.app,
            size = tile.spec.size,
            live = live,
            flipped = flipped,
            monochromeIcons = monochromeIcons,
            icons = viewModel.icons,
            cellSize = cell,
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
