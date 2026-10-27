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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileFolders
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.live.MediaSource
import io.github.danielphingston.metro.live.NotificationHub
import io.github.danielphingston.metro.ui.applist.drawMagnifier
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.StatusBarScrim
import io.github.danielphingston.metro.ui.tiles.FolderTile
import io.github.danielphingston.metro.ui.tiles.PeopleTile
import io.github.danielphingston.metro.ui.tiles.PeopleTiles
import io.github.danielphingston.metro.ui.tiles.PhotosTile
import io.github.danielphingston.metro.ui.tiles.PhotosTiles
import io.github.danielphingston.metro.ui.tiles.QuickSettingPicker
import io.github.danielphingston.metro.ui.tiles.QuickSettingTile
import io.github.danielphingston.metro.ui.tiles.QuickSettingTiles
import io.github.danielphingston.metro.ui.tiles.ShortcutTile
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import io.github.danielphingston.metro.ui.tiles.WeatherTile
import io.github.danielphingston.metro.ui.tiles.WeatherTiles
import io.github.danielphingston.metro.ui.tiles.WidgetEditBadges
import io.github.danielphingston.metro.ui.tiles.WidgetPicker
import io.github.danielphingston.metro.ui.tiles.WidgetTile
import io.github.danielphingston.metro.ui.tiles.WidgetTiles
import io.github.danielphingston.metro.ui.tiles.widgetCustomActions
import io.github.danielphingston.metro.live.CalendarSource
import io.github.danielphingston.metro.live.ClockSource
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.ui.AppLauncher
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * The cell the dragged tile would be dropped on right now, and the move the other tiles are
     * previewing. The preview trails [dragCell] by a short hold and outlives the drag until the
     * dropped layout arrives; see [TilePlacement].
     */
    var dragCell by mutableStateOf<GridPos?>(null)
        internal set
    var preview by mutableStateOf<TileMove?>(null)
        internal set

    /**
     * Widget tile being resized with its handle, and the footprint it would take if released now.
     * Only the outline ([ResizeGhost]) follows the finger; the tile itself changes on release.
     */
    var resizeId by mutableStateOf<Long?>(null)
        internal set
    var resizeSpan by mutableStateOf<TileSpan?>(null)
        internal set

    fun select(id: Long) {
        editing = true
        selectedId = id
    }

    /** Folder currently expanded on the start screen, if any. */
    var openFolderId by mutableStateOf<Long?>(null)

    /** The "add a tile" menu and the widget and quick-settings pickers it can open. */
    var addMenuOpen by mutableStateOf(false)
    var widgetPickerOpen by mutableStateOf(false)
    var quickPickerOpen by mutableStateOf(false)

    /** Folder whose name is being edited. */
    var renamingFolderId by mutableStateOf<Long?>(null)

    /**
     * Grouping mode, which makes or fills a folder: the tile the group is built around (a folder
     * to add to, or an app or shortcut to start a new one) and the tiles picked for it, in the
     * order they were tapped. See [TileFolders.groupIntoFolder].
     */
    var groupingAnchorId by mutableStateOf<Long?>(null)
        private set
    var groupingSelection by mutableStateOf<List<Long>>(emptyList())
        private set

    val grouping: Boolean get() = groupingAnchorId != null

    /** Starts grouping around tile [anchorId]. The open folder closes, as it does for a drag. */
    fun startGrouping(anchorId: Long) {
        select(anchorId)
        openFolderId = null
        groupingSelection = emptyList()
        groupingAnchorId = anchorId
    }

    /** Picks tile [id] for the group, or takes it out again. */
    fun toggleGrouped(id: Long) {
        groupingSelection = if (id in groupingSelection) groupingSelection - id else groupingSelection + id
    }

    /** Keeps only the picked tiles that are among [existing], e.g. after one was unpinned. */
    fun keepGrouped(existing: Set<Long>) {
        if (groupingSelection.any { it !in existing }) groupingSelection = groupingSelection.filter { it in existing }
    }

    fun endGrouping() {
        groupingAnchorId = null
        groupingSelection = emptyList()
    }

    fun exitEdit() {
        editing = false
        selectedId = null
        dragId = null
        dragCell = null
        preview = null
        resizeId = null
        resizeSpan = null
        endGrouping()
    }

    /** Whether a flyout (the grouping bar included) is over the start screen; the pager must not swipe it away. */
    val flyoutOpen: Boolean get() = addMenuOpen || widgetPickerOpen || quickPickerOpen || renamingFolderId != null || grouping

    /** Closes the flyouts (add-tile menu, widget picker, rename field, grouping); returns true if one was open. */
    fun dismissFlyouts(): Boolean {
        val any = flyoutOpen
        addMenuOpen = false
        widgetPickerOpen = false
        quickPickerOpen = false
        renamingFolderId = null
        endGrouping()
        return any
    }

    /** Closes all transient UI, the open folder included; returns true if anything was open. */
    fun dismissOverlays(): Boolean {
        val any = flyoutOpen || openFolderId != null
        dismissFlyouts()
        openFolderId = null
        return any
    }
}

@Composable
fun rememberStartState(): StartState {
    val scroll = rememberScrollState()
    return remember(scroll) { StartState(scroll) }
}

private val SidePadding = 8.dp
private val TileGap = 4.dp

/** How long a dragged tile must stay over a cell before the other tiles move out of its way, so they don't scatter while it passes over them. */
private const val PREVIEW_HOLD_MILLIS = 120L

/** The bottom strip of a folder tile, as a fraction of a cell, that renames the folder when tapped in edit mode. */
private const val FOLDER_NAME_STRIP = 0.32f

/** The centre of the dragged tile, in grid cells. */
private fun dragCenterCell(state: StartState, placement: Placement, cellPx: Float): Offset =
    (state.dragTopLeft + Offset(placement.width * cellPx / 2, placement.height * cellPx / 2)) / cellPx

@Composable
fun StartScreen(
    viewModel: LauncherViewModel,
    state: StartState,
    isCurrentPage: Boolean,
    launcher: AppLauncher,
    onShowApps: () -> Unit,
    onSearch: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadedTiles by viewModel.tiles.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val calendarGranted by viewModel.calendarGranted.collectAsStateWithLifecycle()
    val quickUnlocked by viewModel.quickTilesUnlocked.collectAsStateWithLifecycle()
    val tiles = loadedTiles.orEmpty()

    // Live sources run only when a tile needs them and only while the launcher is visible
    // (collectAsStateWithLifecycle stops collecting below STARTED).
    val openFolder = tiles.firstOrNull { it.spec.id == state.openFolderId && it.spec.content is TileContent.Folder }
    val openChildren = openFolder?.children.orEmpty()
    // An open folder's children are live tiles like any other; a closed folder shows only icons.
    val appPackages = (tiles + openChildren).mapNotNull { it.spec.appKey?.packageName }
    val needsClock = appPackages.any { it == defaults.clock || it == defaults.calendar }
    val needsCalendar = calendarGranted && defaults.calendar in appPackages
    val clock by remember(needsClock) {
        if (needsClock) ClockSource.minutes(context) else flowOf(null)
    }.collectAsStateWithLifecycle(null as ClockState?)
    val events by remember(needsCalendar) {
        if (needsCalendar) CalendarSource.upcoming(context) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    // Media sessions need notification access; skip the listener entirely without it or without app tiles.
    val listenerConnected by NotificationHub.connected.collectAsStateWithLifecycle()
    val needsMedia = listenerConnected && appPackages.isNotEmpty()
    val nowPlaying by remember(needsMedia) {
        if (needsMedia) MediaSource.nowPlaying(context) else flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
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
    val openIndex = tiles.indexOfFirst { it === openFolder }.takeIf { it >= 0 }
    val specs = tiles.map { it.spec }
    val preview = state.preview?.takeIf { move -> specs.any { it.id == move.id } }
    // Stored positions settled for this column count; with a drag in progress, as if it were dropped now.
    val settled = remember(specs, preview, columns) {
        if (preview == null) {
            TilePlacement.layout(specs, columns)
        } else {
            TilePlacement.layout(TilePlacement.placeAt(specs, preview.id, preview.to), columns, first = preview.id)
        }
    }
    val layout = remember(settled, openIndex, openChildren.map { it.spec.size }, columns) {
        FolderLayout.compute(settled, openIndex, openChildren.map { it.spec.size }, columns)
    }
    val placements = layout.tiles
    // Where the tile being resized would land, snapped to the grid.
    val resizeSpan = state.resizeSpan
    val resizeId = state.resizeId
    val ghost = remember(specs, resizeId, resizeSpan, columns) {
        if (resizeId != null && resizeSpan != null) {
            TilePlacement.spanPlacement(specs, resizeId, resizeSpan.width, resizeSpan.height, columns)
        } else {
            null
        }
    }
    // The dropped layout has arrived (or the drop was a no-op): stop previewing.
    LaunchedEffect(specs) { if (state.dragId == null) state.preview = null }
    val flipped = remember { mutableStateMapOf<Long, Boolean>() }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val layoutDirection = LocalLayoutDirection.current
    // In landscape a camera cutout or the navigation bar can sit at the side.
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidesPx = sideInsets.getLeft(density, layoutDirection) + sideInsets.getRight(density, layoutDirection)
        val sides = with(density) { sidesPx.toDp() }
        // Cells grow with the width up to about a phone's, so a tablet shows tiles of the same
        // size, and the grid sits in the middle of a wide screen.
        val cell: Dp = minOf((maxWidth - sides - SidePadding * 2 + TileGap) / columns, WidgetTiles.MAX_CELL_DP.dp)
        val gridWidth = cell * columns - TileGap
        val cellPx = with(density) { cell.toPx() }
        val gapPx = with(density) { TileGap.toPx() }
        val viewportPx = with(density) { maxHeight.toPx() }
        val topInsetPx = WindowInsets.statusBars.getTop(density) + with(density) { 28.dp.toPx() }
        val navInsetPx = WindowInsets.navigationBars.getBottom(density)
        val rows = layout.rows

        // Tiles unpinned or uninstalled while grouping can't be grouped any more.
        LaunchedEffect(loadedTiles) {
            val anchor = state.groupingAnchorId
            if (loadedTiles == null || anchor == null) return@LaunchedEffect
            if (tiles.none { it.spec.id == anchor }) state.endGrouping() else state.keepGrouped(tiles.mapTo(HashSet()) { it.spec.id })
        }
        // A folder that was unpinned or dissolved while open no longer has anything to show.
        LaunchedEffect(openFolder, loadedTiles) {
            if (loadedTiles != null && openFolder == null) state.openFolderId = null
        }
        // Opening a folder near the bottom scrolls its children into view.
        LaunchedEffect(openFolder?.spec?.id) {
            val region = layout.region ?: return@LaunchedEffect
            val folder = openIndex?.let(placements::getOrNull) ?: return@LaunchedEffect
            val inset = navInsetPx + with(density) { 16.dp.toPx() }
            val folderTop = topInsetPx + folder.y * cellPx
            val target = (topInsetPx + (region.y + region.height) * cellPx + inset - viewportPx).coerceAtMost(folderTop).toInt()
            if (target <= state.scroll.value) return@LaunchedEffect
            // The grid grows on the next layout pass; wait for room to scroll into.
            withTimeoutOrNull(500) { snapshotFlow { state.scroll.maxValue }.first { it >= target } }
            state.scroll.animateScrollTo(target)
        }
        // Which live tiles are on screen: only those may flip.
        val liveTiles by rememberUpdatedState(
            (tiles.zip(placements) + openChildren.zip(layout.children)).mapNotNull { (tile, placement) ->
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

        DragTracking(state, tiles, cellPx, columns)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(state.scroll)
                .pointerInput(Unit) {
                    detectTapGestures {
                        when {
                            state.grouping -> state.endGrouping()
                            state.editing -> state.exitEdit()
                            else -> state.openFolderId = null
                        }
                    }
                }
                .windowInsetsPadding(sideInsets),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                            columns = columns,
                            state = state,
                            live = liveFor(tile),
                            flipped = flipped[tile.spec.id] == true && !state.editing,
                            monochromeIcons = settings.monochromeIcons,
                            icons = viewModel.icons,
                            onUnpin = { viewModel.unpin(tile.spec.id) },
                            onResize = { viewModel.resize(tile.spec.id) },
                            onSetSpan = { w, h -> viewModel.setSpan(tile.spec.id, w, h) },
                            onLaunch = { bounds -> activateTile(context, tile, bounds, launcher, defaults, state, quickUnlocked) },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrop = { completed ->
                                val cell = state.dragCell
                                state.preview = null
                                if (completed && cell != null) {
                                    // Keep showing the drop until the stored layout catches up, unless it changes nothing.
                                    if (TilePlacement.moveTo(specs, tile.spec.id, cell, columns) != specs) {
                                        state.preview = TileMove(tile.spec.id, cell)
                                        viewModel.moveTo(tile.spec.id, cell.x, cell.y)
                                    }
                                }
                            },
                        )
                    }
                }
                val folderPlacement = openIndex?.let { placements[it] }
                if (layout.region != null && folderPlacement != null) {
                    FolderRegion(layout.region, folderPlacement, columns, cell, TileGap, SidePadding)
                }
                val appearFrom = folderPlacement?.let {
                    IntOffset((it.x * cellPx).roundToInt(), (it.y * cellPx).roundToInt())
                }
                openChildren.forEachIndexed { index, child ->
                    val placement = layout.children[index]
                    androidx.compose.runtime.key(child.spec.id) {
                        TileHost(
                            tile = child,
                            placement = placement,
                            cellPx = cellPx,
                            gapPx = gapPx,
                            cell = cell,
                            columns = columns,
                            state = state,
                            live = liveFor(child),
                            flipped = flipped[child.spec.id] == true && !state.editing,
                            monochromeIcons = settings.monochromeIcons,
                            icons = viewModel.icons,
                            onUnpin = { viewModel.unpin(child.spec.id) },
                            onResize = { viewModel.resize(child.spec.id) },
                            onSetSpan = { w, h -> viewModel.setSpan(child.spec.id, w, h) },
                            onLaunch = { bounds -> activateTile(context, child, bounds, launcher, defaults, state, quickUnlocked) },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            appearFrom = appearFrom,
                            onRemoveFromFolder = { viewModel.removeFromFolder(openFolder!!.spec.id, child.spec.id) },
                            onDragMove = {
                                // Children reorder among themselves; dropping one outside the region takes it out.
                                val (cx, cy) = dragCenterCell(state, placement, cellPx)
                                if (layout.region?.contains(cx, cy) == true) {
                                    val sibling = openChildren.indices.firstOrNull { i ->
                                        openChildren[i].spec.id != child.spec.id && layout.children[i].contains(cx, cy)
                                    }
                                    sibling?.let {
                                        viewModel.moveInFolder(openFolder!!.spec.id, child.spec.id, openChildren[it].spec.id)
                                    }
                                }
                            },
                            onDrop = { completed ->
                                val (cx, cy) = dragCenterCell(state, placement, cellPx)
                                if (completed && layout.region?.contains(cx, cy) != true) {
                                    viewModel.removeFromFolder(openFolder!!.spec.id, child.spec.id)
                                }
                            },
                        )
                    }
                }
                val selectedIndex = tiles.indexOfFirst { it.spec.id == state.selectedId }
                val selectedChild = openChildren.indexOfFirst { it.spec.id == state.selectedId }
                if (ghost != null) ResizeGhost(ghost, cellPx, gapPx)
                if (state.editing && state.dragId == null && !state.grouping && selectedIndex >= 0) {
                    // Widgets resize with their own drag handle instead of stepping through sizes.
                    val selectedTile = tiles[selectedIndex]
                    val isWidget = selectedTile.spec.content is TileContent.Widget
                    EditBadges(
                        placement = placements[selectedIndex],
                        cellPx = cellPx,
                        gapPx = gapPx,
                        onUnpin = {
                            viewModel.unpin(tiles[selectedIndex].spec.id)
                            state.exitEdit()
                        },
                        onResize = if (isWidget) null else ({ viewModel.resize(tiles[selectedIndex].spec.id) }),
                        onGroup = if (TileFolders.canGroup(selectedTile.spec)) ({ state.startGrouping(selectedTile.spec.id) }) else null,
                        groupLabel = if (selectedTile.spec.content is TileContent.Folder) "add to folder" else "group into folder",
                    )
                    WidgetEditBadges(
                        tile = tiles[selectedIndex],
                        placement = placements[selectedIndex],
                        cellPx = cellPx,
                        gapPx = gapPx,
                        cellDp = cell.value,
                        columns = columns,
                        state = state,
                        onSetSpan = { w, h -> viewModel.setSpan(tiles[selectedIndex].spec.id, w, h) },
                    )
                } else if (state.editing && state.dragId == null && !state.grouping && selectedChild >= 0) {
                    EditBadges(
                        placement = layout.children[selectedChild],
                        cellPx = cellPx,
                        gapPx = gapPx,
                        onUnpin = {
                            viewModel.unpin(openChildren[selectedChild].spec.id)
                            state.exitEdit()
                        },
                        onResize = { viewModel.resize(openChildren[selectedChild].spec.id) },
                    )
                }
            }

            Row(
                Modifier.width(gridWidth + SidePadding * 2).padding(SidePadding + 4.dp, 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AddTileButton(onClick = { state.addMenuOpen = true })
                SearchButton(onSearch)
                AllAppsArrow(onShowApps)
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            // Room to scroll the last tiles above the grouping bar.
            if (state.grouping) Spacer(Modifier.height(GroupingBarHeight))
        }

        // Tiles scroll up under the status bar; keep its clock and icons legible.
        StatusBarScrim(overPhoto = LocalStartBackdrop.current.mode.let {
            it == StartBackground.PHOTO_FULL || it == StartBackground.SYSTEM_WALLPAPER
        })

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
                onQuickSettings = {
                    state.addMenuOpen = false
                    state.quickPickerOpen = true
                },
                onDismiss = { state.addMenuOpen = false },
            )
        }
        if (state.widgetPickerOpen) {
            WidgetPicker(viewModel, onDismiss = { state.widgetPickerOpen = false })
        }
        if (state.quickPickerOpen) {
            QuickSettingPicker(viewModel, onDismiss = { state.quickPickerOpen = false })
        }
        if (state.grouping) {
            GroupingBar(
                anchorIsFolder = tiles.firstOrNull { it.spec.id == state.groupingAnchorId }?.spec?.content is TileContent.Folder,
                count = state.groupingSelection.size,
                onConfirm = {
                    // The new or filled folder is left selected, ready to rename.
                    val folderId = state.groupingAnchorId?.let { viewModel.groupIntoFolder(it, state.groupingSelection) }
                    state.endGrouping()
                    folderId?.let(state::select)
                },
                onCancel = state::endGrouping,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        tiles.firstOrNull { it.spec.id == state.renamingFolderId }?.let { folder ->
            FolderRename((folder.spec.content as? TileContent.Folder)?.name.orEmpty()) { name ->
                viewModel.renameFolder(folder.spec.id, name)
                state.renamingFolderId = null
            }
        }
    }
}

/**
 * Follows a dragged top-level tile (also while the screen scrolls under it) and works out what
 * dropping it would do: it always targets the cell its corner snaps to ([StartState.dragCell]),
 * and the other tiles make room for it once it has rested there for a moment. Dragging only ever
 * moves a tile; folders are made in grouping mode.
 */
@Composable
internal fun DragTracking(state: StartState, tiles: List<ResolvedTile>, cellPx: Float, columns: Int) {
    val currentTiles by rememberUpdatedState(tiles)

    LaunchedEffect(state.dragId) {
        val id = state.dragId ?: return@LaunchedEffect
        snapshotFlow { state.dragTopLeft }.collect { topLeft ->
            val spec = currentTiles.firstOrNull { it.spec.id == id }?.spec
                ?: return@collect // A folder child; those are handled by the child's own drag.
            state.dragCell = TilePlacement.dropCell(spec, topLeft.x / cellPx, topLeft.y / cellPx, columns)
        }
    }
    // The other tiles make room once the dragged one has rested on a cell for a moment.
    LaunchedEffect(state.dragCell) {
        val id = state.dragId ?: return@LaunchedEffect
        val cell = state.dragCell ?: return@LaunchedEffect
        delay(PREVIEW_HOLD_MILLIS)
        state.preview = TileMove(id, cell)
    }
}

/** One tile on the start grid: its gestures, accessibility actions, edit mode visuals and body. */
@Composable
internal fun TileHost(
    tile: ResolvedTile,
    placement: Placement,
    cellPx: Float,
    gapPx: Float,
    cell: Dp,
    columns: Int,
    state: StartState,
    live: LiveContent?,
    flipped: Boolean,
    monochromeIcons: Boolean,
    icons: IconCache,
    onUnpin: () -> Unit,
    onResize: () -> Unit,
    onSetSpan: (width: Int, height: Int) -> Unit,
    onLaunch: (Rect?) -> Unit,
    onLongPress: () -> Unit,
    onDragMove: () -> Unit = {},
    /** Where a folder's child slides out from when the folder opens. */
    appearFrom: IntOffset? = null,
    /** Set for the children of an open folder. */
    onRemoveFromFolder: (() -> Unit)? = null,
    onDrop: (completed: Boolean) -> Unit = {},
) {
    val id = tile.spec.id
    val folder = tile.spec.content as? TileContent.Folder
    // A hosted widget does its own touch handling and accessibility, and is never flipped or tilted.
    val isWidget = tile.spec.content is TileContent.Widget
    val widgetActions = widgetCustomActions(tile, placement, columns, cell.value, onSetSpan)
    val target = IntOffset((placement.x * cellPx).roundToInt(), (placement.y * cellPx).roundToInt())
    val position = remember { Animatable(appearFrom ?: target, IntOffset.VectorConverter) }
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
    // While grouping, the anchor and the tiles that can join it stand out and the rest dim.
    val grouping = state.grouping
    val groupAnchor = grouping && state.groupingAnchorId == id
    val groupTarget = grouping && !groupAnchor && onRemoveFromFolder == null && TileFolders.canHold(tile.spec)
    val picked = groupTarget && id in state.groupingSelection
    val scale by animateFloatAsState(
        when {
            dragging -> 1.04f
            grouping -> if (groupAnchor || picked) 1f else 0.94f
            state.editing && !selected -> 0.94f
            else -> 1f
        },
        label = "tileScale",
    )
    var shown by remember { mutableStateOf(appearFrom == null) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        when {
            !shown -> 0f
            grouping -> if (groupAnchor || groupTarget) 1f else 0.3f
            state.editing && !selected -> 0.6f
            else -> 1f
        },
        label = "tileAlpha",
    )
    var bounds by remember { mutableStateOf<Rect?>(null) }

    val currentPlacement by rememberUpdatedState(placement)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentGroupTarget by rememberUpdatedState(groupTarget)
    val folderOpen = state.openFolderId == id
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
            .turnstile(LocalTurnstile.current, id) { bounds }
            .semantics(mergeDescendants = !isWidget) {
                contentDescription = buildString {
                    if (folder != null) append("${tile.label}, folder, ${tile.children.size} items") else append(tile.label)
                    (live as? LiveContent.Notifications)?.let { append(", ${it.data.count} new") }
                }
                if (grouping) {
                    // Grouping mode: tiles are picked, not opened; only those that can join respond.
                    if (groupTarget) {
                        onClick { state.toggleGrouped(id); true }
                        stateDescription = if (picked) "selected" else "not selected"
                    } else if (!groupAnchor) {
                        disabled()
                    }
                    return@semantics
                }
                if (!isWidget) onClick {
                    // Assistive launches skip the tap handler that normally ends edit mode.
                    state.exitEdit()
                    onLaunch(bounds)
                    true
                }
                onLongClick("edit") { state.select(id); true }
                customActions = buildList {
                    if (onRemoveFromFolder == null && TileFolders.canGroup(tile.spec)) {
                        add(CustomAccessibilityAction("Group into folder…") { state.startGrouping(id); true })
                    }
                    if (folder != null) {
                        add(CustomAccessibilityAction(if (folderOpen) "Close" else "Open") {
                            state.openFolderId = if (folderOpen) null else id
                            true
                        })
                    }
                    onRemoveFromFolder?.let { add(CustomAccessibilityAction("Remove from folder") { it(); true }) }
                    add(CustomAccessibilityAction("Unpin from start") { onUnpin(); true })
                    if (onRemoveFromFolder == null && !isWidget) add(CustomAccessibilityAction("Resize") { onResize(); true })
                    addAll(widgetActions)
                }
            }
            .pointerInput(id) {
                // Long-press picks a tile up (entering edit mode); in edit mode the selected
                // tile can be dragged straight away.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Tiles are picked, not moved, while grouping.
                    if (state.grouping) return@awaitEachGesture
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
                    state.preview = null
                    // Tiles are placed by grid position, which an open folder's region would shift; close it.
                    if (onRemoveFromFolder == null) state.openFolderId = null
                    var dropped = false
                    try {
                        val completed = drag(start.id) { change ->
                            state.dragTopLeft += change.positionChange()
                            change.consume()
                            currentOnDragMove()
                        }
                        currentOnDrop(completed)
                        dropped = true
                    } finally {
                        // Also when the tile leaves the screen mid-drag (e.g. its app is uninstalled):
                        // a stuck dragId would keep the auto-scroll loop running every frame.
                        if (state.dragId == id) {
                            state.dragId = null
                            state.dragCell = null
                            // A finished drop keeps its preview until the stored layout catches up.
                            if (!dropped) state.preview = null
                        }
                    }
                }
            }
            .pointerInput(id, cellPx) {
                detectTapGestures { offset ->
                    if (state.grouping) {
                        if (currentGroupTarget) state.toggleGrouped(id)
                    } else if (state.editing) {
                        // The name strip of a folder opens the rename field.
                        if (folder != null && offset.y > size.height - cellPx * FOLDER_NAME_STRIP) {
                            state.renamingFolderId = id
                        } else if (state.selectedId != id) {
                            state.select(id)
                        }
                    } else {
                        onLaunch(bounds)
                    }
                }
            }
            // Constant for a tile, so edit mode (which turns the tilt off) never reshapes the modifier chain mid-gesture.
            .then(if (isWidget) Modifier else Modifier.metroTilt(enabled = !state.editing)),
    ) {
        TileBody(
            tile, live, flipped, monochromeIcons, icons, cell, folderOpen,
            editing = state.editing,
            onWidgetLongPress = { onLongPress(); state.select(id) },
        )
        if (groupAnchor) Box(Modifier.fillMaxSize().border(2.dp, Metro.colors.foreground))
        if (groupTarget) GroupCheckbox(picked, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
internal fun EditBadges(
    placement: Placement,
    cellPx: Float,
    gapPx: Float,
    onUnpin: () -> Unit,
    onResize: (() -> Unit)?,
    onGroup: (() -> Unit)? = null,
    groupLabel: String = "group into folder",
) {
    val badgePx = with(LocalDensity.current) { EditBadgeSize.toPx() }
    val right = (placement.x + placement.width) * cellPx - gapPx
    val bottom = (placement.y + placement.height) * cellPx - gapPx
    val top = placement.y * cellPx
    val left = placement.x * cellPx
    // Group: top-left corner, where widgets (which can't be grouped) have their settings badge.
    if (onGroup != null) EditBadge(
        label = groupLabel,
        onClick = onGroup,
        modifier = Modifier.offset { IntOffset((left - badgePx * 0.15f).roundToInt(), (top - badgePx * 0.3f).roundToInt()) },
    ) { color, stroke -> drawFolderGlyph(color, stroke) }
    // Unpin: top-right corner.
    EditBadge(
        label = "unpin",
        onClick = onUnpin,
        modifier = Modifier.offset { IntOffset((right - badgePx * 0.85f).roundToInt(), (top - badgePx * 0.3f).roundToInt()) },
    ) { color, stroke ->
        val inset = size.minDimension * 0.3f
        drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
    }
    // Resize: bottom-right corner, arrow pointing to the next size.
    if (onResize != null) EditBadge(
        label = "resize",
        onClick = onResize,
        modifier = Modifier.offset { IntOffset((right - badgePx * 0.85f).roundToInt(), (bottom - badgePx * 0.7f).roundToInt()) },
    ) { color, stroke ->
        val inset = size.minDimension * 0.3f
        val a = Offset(inset, inset)
        val b = Offset(size.width - inset, size.height - inset)
        val head = size.minDimension * 0.2f
        drawLine(color, a, b, stroke, StrokeCap.Round)
        drawLine(color, b, b - Offset(head, 0f), stroke, StrokeCap.Round)
        drawLine(color, b, b - Offset(0f, head), stroke, StrokeCap.Round)
    }
}

internal val EditBadgeSize = 30.dp

/** A filled circle with a thin ring in the page colour, so it stands out from any tile; [glyph] draws on it. */
@Composable
internal fun EditBadge(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    glyph: DrawScope.(color: Color, stroke: Float) -> Unit,
) {
    val colors = Metro.colors
    Box(
        modifier
            .size(EditBadgeSize)
            .background(colors.foreground, CircleShape)
            .border(2.dp, colors.background, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = label }
            .drawBehind { glyph(colors.background, 1.5.dp.toPx()) },
    )
}

/** The outline of the footprint a tile being resized takes on release, [placement] in grid cells. */
@Composable
internal fun ResizeGhost(placement: Placement, cellPx: Float, gapPx: Float) {
    val colors = Metro.colors
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset((placement.x * cellPx).roundToInt(), (placement.y * cellPx).roundToInt()) }
            .size(
                with(density) { (placement.width * cellPx - gapPx).toDp() },
                with(density) { (placement.height * cellPx - gapPx).toDp() },
            )
            .background(colors.foreground.copy(alpha = 0.12f))
            .border(2.dp, colors.foreground),
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
        is TileContent.App -> tile.app?.let { TileFace(it, tile.spec.size, live, flipped, monochromeIcons, icons, cell) }
        is TileContent.Shortcut -> ShortcutTile(tile, cell, icons, monochromeIcons)
        is TileContent.Widget -> WidgetTile(tile, cell, editing, onWidgetLongPress)
        TileContent.Weather -> WeatherTile(tile, cell, flipped)
        TileContent.Photos -> PhotosTile(tile, cell, flipped)
        TileContent.People -> PeopleTile(tile, cell, flipped)
        is TileContent.QuickSetting -> QuickSettingTile(tile, cell)
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
    quickUnlocked: Boolean,
) {
    when (val content = tile.spec.content) {
        is TileContent.App -> tile.app?.let { launcher.launch(it, bounds) }
        is TileContent.Shortcut -> launcher.open(bounds) { screenBounds, options ->
            ShortcutTiles.launch(context, content, screenBounds, options)
        }
        is TileContent.Widget -> Unit // The hosted widget handles its own taps.
        TileContent.Weather -> WeatherTiles.intent(context)?.let { launcher.start(it, bounds) }
        TileContent.Photos -> PhotosTiles.intent(context, defaults)?.let { launcher.start(it, bounds) }
        TileContent.People -> PeopleTiles.intent(context)?.let { launcher.start(it, bounds) }
        is TileContent.QuickSetting -> QuickSettingTiles.activate(context, content.kind, quickUnlocked)
            .takeIf { it.isNotEmpty() }
            ?.let { intents -> QuickSettingTiles.open(context, content.kind, intents, bounds, launcher) }
        is TileContent.Folder -> state.openFolderId = if (state.openFolderId == tile.spec.id) null else tile.spec.id
    }
}

/** Menu for tiles that don't come from the app list. */
@Composable
private fun AddTileMenu(
    onAdd: (TileContent, TileSize) -> Unit,
    onWidgets: () -> Unit,
    onQuickSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    MetroFlyout(onDismiss) {
        MetroMenu(
            items = listOf(
                "weather" to { onAdd(TileContent.Weather, TileSize.WIDE) },
                "photos" to { onAdd(TileContent.Photos, TileSize.WIDE) },
                "people" to { onAdd(TileContent.People, TileSize.MEDIUM) },
                "quick settings" to onQuickSettings,
                "widgets" to onWidgets,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        )
    }
}

/** A thin circle under the tiles holding a hairline glyph; kept quiet so the tiles stay the focus. */
@Composable
private fun RoundGlyphButton(
    label: String,
    onClick: () -> Unit,
    glyph: DrawScope.(color: Color, stroke: Float) -> Unit,
) {
    val color = Metro.colors.foreground.copy(alpha = 0.6f)
    Box(
        Modifier
            .size(40.dp)
            .metroTilt()
            .border(1.5.dp, color, CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .drawBehind { glyph(color, 1.5.dp.toPx()) },
    )
}

/** Circled plus that opens [AddTileMenu]. */
@Composable
private fun AddTileButton(onClick: () -> Unit) = RoundGlyphButton("add a tile", onClick) { color, stroke ->
    val c = size.width / 2
    val arm = size.width * 0.18f
    drawLine(color, Offset(c - arm, c), Offset(c + arm, c), stroke, StrokeCap.Round)
    drawLine(color, Offset(c, c - arm), Offset(c, c + arm), stroke, StrokeCap.Round)
}

/** Circled magnifier that opens the search overlay. */
@Composable
private fun SearchButton(onClick: () -> Unit) = RoundGlyphButton("search", onClick) { color, _ ->
    inset(size.width * 0.28f) { drawMagnifier(color) }
}

/** The circled arrow under the tiles that opens the app list. */
@Composable
private fun AllAppsArrow(onClick: () -> Unit) = RoundGlyphButton("all apps", onClick) { color, stroke ->
    val y = size.height / 2
    val l = size.width * 0.3f
    val r = size.width * 0.7f
    val head = size.width * 0.15f
    drawLine(color, Offset(l, y), Offset(r, y), stroke, StrokeCap.Round)
    drawLine(color, Offset(r, y), Offset(r - head, y - head), stroke, StrokeCap.Round)
    drawLine(color, Offset(r, y), Offset(r - head, y + head), stroke, StrokeCap.Round)
}
