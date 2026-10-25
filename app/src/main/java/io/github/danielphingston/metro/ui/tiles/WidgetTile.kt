package io.github.danielphingston.metro.ui.tiles

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.WidgetConfigActivity
import io.github.danielphingston.metro.ui.start.EditBadge
import io.github.danielphingston.metro.ui.start.EditBadgeSize
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.StartState
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** What a provider declares about its size, in dp. Target cells (in its own launcher's grid) are 0 when it gives none. */
internal data class WidgetDims(
    val minWidth: Float,
    val minHeight: Float,
    val minResizeWidth: Float,
    val minResizeHeight: Float,
    val targetCols: Int,
    val targetRows: Int,
    /** The largest size the widget can be resized to, in dp; 0 when the provider sets no limit. */
    val maxResizeWidth: Float = 0f,
    val maxResizeHeight: Float = 0f,
    /** Which axes the widget can be resized along: [AppWidgetProviderInfo.RESIZE_HORIZONTAL] and/or [AppWidgetProviderInfo.RESIZE_VERTICAL]. */
    val resizeMode: Int = AppWidgetProviderInfo.RESIZE_BOTH,
)

/**
 * The cell sizes a widget tile can be resized to, both ends inclusive. An axis the widget can't
 * be resized along has equal ends.
 */
internal data class SpanLimits(val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int) {
    /** Whether there is anything to resize. */
    val resizable: Boolean get() = maxWidth > minWidth || maxHeight > minHeight

    fun clamp(width: Int, height: Int) = TileSpan(width.coerceIn(minWidth, maxWidth), height.coerceIn(minHeight, maxHeight))

    /** These limits for a tile with only [columns] columns to grow into. */
    fun within(columns: Int): SpanLimits {
        val max = maxWidth.coerceIn(1, columns.coerceAtLeast(1))
        return copy(minWidth = minWidth.coerceAtMost(max), maxWidth = max)
    }
}

/** Hosts Android app widgets inside tiles. One [AppWidgetHost] serves the whole app. */
object WidgetTiles {
    /** Identifies this launcher to the system's widget service; allocated ids belong to it. */
    private const val HOST_ID = 0x4D455452

    private var host: AppWidgetHost? = null

    /** Widget tiles currently on a STARTED screen. Main thread only. */
    private var listeners = 0

    fun host(context: Context): AppWidgetHost = host ?: object : AppWidgetHost(context.applicationContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?) =
            MetroHostView(context)
    }.also { host = it }

    /** Frees the app widget id when its tile is unpinned. */
    fun release(context: Context, appWidgetId: Int) {
        try {
            host(context).deleteAppWidgetId(appWidgetId)
        } catch (_: RuntimeException) {
            // The widget service is gone (rethrown as a RuntimeException); the id stays allocated until the app's data is cleared.
        }
    }

    /** The widget's provider, or null if the id is invalid or the provider is gone. */
    internal fun providerInfo(context: Context, appWidgetId: Int): AppWidgetProviderInfo? = try {
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)
    } catch (_: RuntimeException) {
        null // The widget service didn't answer: the tile shows its placeholder.
    }

    /**
     * Widgets only update while the host listens, which costs a binder callback per update.
     * So it listens while at least one widget tile is on a STARTED screen. Listening again after
     * a stop makes the system send every widget's latest views, so they are fresh on return.
     *
     * Both calls are binder transactions that the host turns into a RuntimeException on failure,
     * and startListening carries every widget's views at once: a few large bitmaps in them are
     * enough for a TransactionTooLargeException. That must not crash the launcher; the widgets
     * just keep what they last showed until the next start.
     */
    internal fun startListening(context: Context) {
        if (listeners++ == 0) {
            try {
                host(context).startListening()
            } catch (_: RuntimeException) {
                // See above.
            }
        }
    }

    internal fun stopListening(context: Context) {
        if (listeners > 0 && --listeners == 0) {
            try {
                host(context).stopListening()
            } catch (_: RuntimeException) {
                // See startListening.
            }
        }
    }

    /**
     * The host view for a widget tile. A widget whose views the system can't hand over (too large
     * for a transaction, widget service dying) gets an empty view, so only its tile is blank.
     */
    internal fun createView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): View = try {
        host(context).createView(context, appWidgetId, info)
    } catch (_: RuntimeException) {
        View(context)
    }

    /**
     * The options a stock launcher gives a widget of [size] (in dp): the legacy min and max
     * sizes many widgets still read, the size list that responsive layouts pick from, and the
     * home screen category. The screen is portrait only, so the min and max sizes are equal.
     */
    internal fun sizeOptions(size: SizeF): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.height.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size.width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.height.toInt())
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
        putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    }

    /** What the provider declares about its size. */
    internal fun dimsOf(context: Context, info: AppWidgetProviderInfo): WidgetDims {
        val density = context.resources.displayMetrics.density
        return WidgetDims(
            minWidth = info.minWidth / density,
            minHeight = info.minHeight / density,
            minResizeWidth = info.minResizeWidth / density,
            minResizeHeight = info.minResizeHeight / density,
            targetCols = info.targetCellWidth,
            targetRows = info.targetCellHeight,
            maxResizeWidth = info.maxResizeWidth / density,
            maxResizeHeight = info.maxResizeHeight / density,
            resizeMode = info.resizeMode,
        )
    }

    /** The width of a start screen cell in dp: the screen less its side padding, split into [columns]. */
    internal fun cellDp(screenWidthDp: Int, columns: Int): Float = (screenWidthDp - 12f) / columns

    /**
     * The footprint a new tile for the widget starts at, in cells of a grid of [columns] columns
     * and [cell] dp (a tile is [cell] per cell less a 4dp gap). It never goes below the widget's
     * minimum size, but a provider that declares target cells gets that many of our cells on each
     * axis when its minimum is at most [TARGET_TOLERANCE] times that footprint: stock launchers
     * give such widgets one of their cells, which can be a little smaller than ours, and they
     * scale down fine. Else it is the smallest number of cells that holds the minimum.
     */
    internal fun suggestedSpan(dims: WidgetDims, cell: Float, columns: Int): TileSpan {
        fun axis(minDp: Float, target: Int, limit: Int): Int {
            val cells = if (target > 0 && minDp <= target * cell * TARGET_TOLERANCE) target else cellsToHold(minDp, cell)
            return cells.coerceIn(1, limit)
        }
        return TileSpan(
            axis(max(dims.minWidth, dims.minResizeWidth), dims.targetCols, columns),
            axis(max(dims.minHeight, dims.minResizeHeight), dims.targetRows, TileSpan.MAX_HEIGHT),
        )
    }

    /** The [TileSize] closest to [span], for what still reads the size of a tile. */
    internal fun nearestSize(span: TileSpan): TileSize =
        TileSize.entries.minWith(
            compareBy<TileSize>({ abs(it.width - span.width) + abs(it.height - span.height) }, { -it.width * it.height }),
        )

    /**
     * The cell sizes a widget with [dims] may take in a grid of [columns] columns and cells of
     * [cell] dp (a tile is [cell] per cell less a 4dp gap). The minimum is the size it can be
     * shrunk to (else its minimum size), at least 1x1, with the same [TARGET_TOLERANCE] as the
     * starting footprint and never above it, so the handle reaches the footprint a new tile
     * starts at. The maximum is the size the provider
     * allows, else the whole width and [TileSpan.MAX_HEIGHT] rows. An axis the widget can't be
     * resized along stays at the tile's current [width] x [height].
     */
    internal fun spanLimits(dims: WidgetDims, cell: Float, columns: Int, width: Int, height: Int): SpanLimits {
        fun leastCells(dp: Float) = ceil(dp / (cell * TARGET_TOLERANCE) - CELL_EPSILON).toInt()
        fun atMost(dp: Float) = floor((dp + TILE_GAP) / cell + CELL_EPSILON).toInt()
        val start = suggestedSpan(dims, cell, columns)
        val minW = leastCells(dims.minResizeWidth.takeIf { it > 0 } ?: dims.minWidth).coerceIn(1, start.width)
        val minH = leastCells(dims.minResizeHeight.takeIf { it > 0 } ?: dims.minHeight).coerceIn(1, start.height)
        val maxW = dims.maxResizeWidth.takeIf { it > 0 }?.let(::atMost)?.coerceIn(minW, columns) ?: columns
        val maxH = dims.maxResizeHeight.takeIf { it > 0 }?.let(::atMost)?.coerceIn(minH, TileSpan.MAX_HEIGHT) ?: TileSpan.MAX_HEIGHT
        val horizontal = dims.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = dims.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val w = width.coerceIn(1, columns)
        val h = height.coerceIn(1, TileSpan.MAX_HEIGHT)
        return SpanLimits(
            minWidth = if (horizontal) minW else w,
            maxWidth = if (horizontal) maxW else w,
            minHeight = if (vertical) minH else h,
            maxHeight = if (vertical) maxH else h,
        )
    }

    /**
     * The footprint a handle drag asks for: the tile's [startWidth] x [startHeight] plus the drag
     * ([dx], [dy] in cells, fractions allowed), snapped to whole cells and kept within [limits].
     */
    internal fun dragSpan(startWidth: Int, startHeight: Int, dx: Float, dy: Float, limits: SpanLimits): TileSpan =
        limits.clamp((startWidth + dx).roundToInt(), (startHeight + dy).roundToInt())

    /** The smallest number of [cell] dp cells whose tile (less the gap) holds [dp]. */
    private fun cellsToHold(dp: Float, cell: Float): Int = ceil((dp + TILE_GAP) / cell - CELL_EPSILON).toInt()

    /**
     * How much bigger than its footprint a widget's declared minimum may be and still start at
     * that footprint. Samsung's battery widget declares 1x1 cells but a minimum of about 80dp,
     * a little over one of our cells at six columns.
     */
    internal const val TARGET_TOLERANCE = 1.25f

    /** Matches the gap between start screen tiles. */
    private const val TILE_GAP = 4f

    /** Slack for float error when a size is exactly a whole number of cells. */
    private const val CELL_EPSILON = 0.01f

    /** Whether the provider has a configuration activity the user can open again for a placed widget. */
    internal fun canReconfigure(info: AppWidgetProviderInfo): Boolean = info.configure != null

    /** Opens the provider's configuration activity for the existing widget; see [WidgetConfigActivity]. */
    internal fun reconfigure(context: Context, appWidgetId: Int) {
        val intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(WidgetConfigActivity.EXTRA_RECONFIGURE, true)
        runCatching { context.startActivity(intent) }
    }

    /** Asks the provider to redraw one widget, after its settings changed. */
    internal fun refresh(context: Context, appWidgetId: Int) {
        val info = providerInfo(context, appWidgetId) ?: return
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setComponent(info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        // Refused for work profile providers; they redraw on their own schedule.
        runCatching { context.sendBroadcast(intent) }
    }

    /** [tiles] with the widget tile [id] drawn on the accent tile (false) or without a tile background (true). */
    internal fun withTransparency(tiles: List<TileSpec>, id: Long, transparent: Boolean): List<TileSpec> =
        tiles.map { spec ->
            val widget = spec.content as? TileContent.Widget
            if (spec.id == id && widget != null) spec.copy(content = widget.copy(transparent = transparent)) else spec
        }

    internal fun setTransparent(context: Context, id: Long, transparent: Boolean) {
        (context.applicationContext as MetroApplication).tiles.edit { withTransparency(it, id, transparent) }
    }
}

/**
 * The host view for widget tiles. On Android 12 and later widgets draw their own rounded
 * background, and stock launchers drop the default padding so the widget gets its whole cell;
 * so does this.
 */
private class MetroHostView(context: Context) : AppWidgetHostView(context) {
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) = super.setPadding(0, 0, 0, 0)
}

/**
 * A hosted app widget filling its tile. Falls back to [PlaceholderTile] if the widget id is
 * invalid or its provider is gone.
 *
 * Gestures: the start screen enters edit mode on long-press and drags tiles while editing, but
 * a hosted view takes touches away from Compose's detectors. So outside edit mode the widget
 * sits in a [WidgetFrame], which spots a long-press the widget swallowed and reports it as
 * [onLongPress]; taps still reach the widget. In edit mode a transparent Compose layer covers
 * the widget, so it gets no touches and the tile's own drag, tap and resize handling works.
 *
 * The widget is told the tile's measured size, so it follows the tile whatever footprint it has.
 */
@Composable
fun WidgetTile(
    tile: ResolvedTile,
    cellSize: Dp,
    editing: Boolean = false,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val widget = tile.spec.content as? TileContent.Widget
    val info = remember(widget?.appWidgetId, tile.app) {
        widget?.let { WidgetTiles.providerInfo(context, it.appWidgetId) }
    }
    if (widget == null || info == null) {
        PlaceholderTile(tile, cellSize)
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var listening = false
        fun stop() {
            if (listening) WidgetTiles.stopListening(context)
            listening = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!listening) {
                    listening = true
                    WidgetTiles.startListening(context)
                }
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }

    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val currentEditing by rememberUpdatedState(editing)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    Box(
        Modifier
            .fillMaxSize()
            .then(if (widget.transparent) Modifier else Modifier.tileSurface())
            .onSizeChanged { sizePx = it },
    ) {
        AndroidView(
            factory = { ctx ->
                val hostView = WidgetTiles.createView(ctx, widget.appWidgetId, info)
                // The system's long-press callback only matters if Compose has not already entered edit mode.
                WidgetFrame(ctx, hostView) { if (!currentEditing) currentOnLongPress() }
            },
            modifier = Modifier.fillMaxSize(),
            update = { frame ->
                // Edit mode covers the widget, so screen readers must not reach it behind the cover either.
                frame.importantForAccessibility =
                    if (editing) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                // Only tell the widget when its size changed, not on every recomposition.
                if (sizePx != IntSize.Zero) {
                    frame.reportSize(SizeF(sizePx.width / density, sizePx.height / density))
                }
            },
        )
        if (editing) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
    }
}

/**
 * Holds the widget's host view and handles a long-press on a widget that took the touch (any
 * widget with a click action does). Once the finger has stayed put for the long-press time the
 * widget is cancelled, so releasing doesn't click it, and the rest of the gesture is swallowed.
 * When the widget ignores the touch, Compose still sees it and detects the long-press itself,
 * so nothing is reported here. When Compose detects it too, it does so first, and the callback
 * is told only afterwards (see the delay) so that it can tell.
 */
@SuppressLint("ViewConstructor")
internal class WidgetFrame(
    context: Context,
    val hostView: View,
    private val onLongPress: () -> Unit,
) : FrameLayout(context) {
    /** The last size passed to the widget, so an unchanged size isn't sent again. */
    var appliedSize: SizeF? = null
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        hostView.dispatchTouchEvent(cancel)
        cancel.recycle()
        onLongPress()
    }

    init {
        addView(hostView)
    }

    /** Passes the tile's size in dp on to the widget, unless it already has it. */
    fun reportSize(size: SizeF) {
        if (appliedSize == size) return
        appliedSize = size
        try {
            (hostView as? AppWidgetHostView)?.updateAppWidgetSize(WidgetTiles.sizeOptions(size), listOf(size))
        } catch (_: RuntimeException) {
            // The widget service refused or is gone (e.g. SecurityException for a widget id it no longer knows).
        }
    }

    /**
     * Sees every event, also those a scrolling widget has shielded from [onInterceptTouchEvent]
     * with requestDisallowInterceptTouchEvent, so the timer can't outlive a scroll or a release.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_MOVE ->
                if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (longPressed && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        val handled = super.dispatchTouchEvent(ev)
        // Only the widget can accept a DOWN: this frame is not clickable.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && handled) {
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + COMPOSE_HEAD_START_MILLIS)
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    internal companion object {
        /** Compose's own long-press detection fires at the long-press time; this lets it go first. */
        const val COMPOSE_HEAD_START_MILLIS = 80L
    }
}

/**
 * The size limits of the widget with provider [info] for a tile at [placement], also capped by
 * the columns to its right, or null if the widget's provider is gone.
 */
private fun widgetSpanLimits(
    context: Context,
    info: AppWidgetProviderInfo?,
    placement: Placement,
    columns: Int,
    cellDp: Float,
): SpanLimits? = info?.let {
    WidgetTiles.spanLimits(WidgetTiles.dimsOf(context, it), cellDp, columns, placement.width, placement.height)
        .within(columns - placement.x)
}

/**
 * The extra edit mode badges of the selected widget tile: on its left corners the widget's own
 * settings (when its provider has any) and whether it sits on the accent tile or draws alone;
 * on its bottom-right corner a handle that resizes it in whole cells, within what the provider
 * allows. While dragging, [state] holds the footprint to outline; the tile is only resized on
 * release, through [onSetSpan], because every size change is reported to the widget.
 */
@Composable
internal fun WidgetEditBadges(
    tile: ResolvedTile,
    placement: Placement,
    cellPx: Float,
    gapPx: Float,
    cellDp: Float,
    columns: Int,
    state: StartState,
    onSetSpan: (width: Int, height: Int) -> Unit,
) {
    val widget = tile.spec.content as? TileContent.Widget ?: return
    val context = LocalContext.current
    val info = remember(widget.appWidgetId) { WidgetTiles.providerInfo(context, widget.appWidgetId) }
    val badgePx = with(LocalDensity.current) { EditBadgeSize.toPx() }
    val left = placement.x * cellPx
    val top = placement.y * cellPx
    val right = (placement.x + placement.width) * cellPx - gapPx
    val bottom = (placement.y + placement.height) * cellPx - gapPx
    if (info != null && WidgetTiles.canReconfigure(info)) {
        EditBadge(
            label = "widget settings",
            onClick = { WidgetTiles.reconfigure(context, widget.appWidgetId) },
            modifier = Modifier.offset { IntOffset((left - badgePx * 0.15f).roundToInt(), (top - badgePx * 0.3f).roundToInt()) },
        ) { color, stroke -> drawGear(color, stroke) }
    }
    EditBadge(
        label = if (widget.transparent) "show on tile" else "hide tile background",
        onClick = { WidgetTiles.setTransparent(context, tile.spec.id, !widget.transparent) },
        modifier = Modifier.offset { IntOffset((left - badgePx * 0.15f).roundToInt(), (bottom - badgePx * 0.7f).roundToInt()) },
    ) { color, stroke -> drawTileBackground(color, stroke, filled = !widget.transparent) }
    val limits = widgetSpanLimits(context, info, placement, columns, cellDp)
    if (limits != null && limits.resizable) {
        EditBadge(
            label = "resize handle",
            onClick = null,
            modifier = Modifier
                .offset { IntOffset((right - badgePx * 0.85f).roundToInt(), (bottom - badgePx * 0.7f).roundToInt()) }
                .resizeHandle(state, tile.spec.id, placement, cellPx, limits, onSetSpan),
        ) { color, stroke -> drawResizeHandle(color, stroke) }
    }
}

/**
 * Drags the tile's bottom-right corner: the footprint [StartState.resizeSpan] follows the finger
 * in whole cells within [limits], and [onSetSpan] gets it on release (not when the gesture is
 * cancelled or nothing changed). The handle itself stays where it is until the new size arrives,
 * so the drag is measured against a fixed point.
 */
private fun Modifier.resizeHandle(
    state: StartState,
    id: Long,
    start: Placement,
    cellPx: Float,
    limits: SpanLimits,
    onSetSpan: (width: Int, height: Int) -> Unit,
): Modifier = composed {
    val currentOnSetSpan by rememberUpdatedState(onSetSpan)
    pointerInput(id, start, cellPx, limits) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A tap on the handle must not reach the screen's tap handler, which leaves edit mode.
            down.consume()
            val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
            var moved = slop.position - down.position
            fun follow() {
                state.resizeId = id
                state.resizeSpan = WidgetTiles.dragSpan(start.width, start.height, moved.x / cellPx, moved.y / cellPx, limits)
            }
            // Tiles below an open folder sit lower than their cells say; close it, as dragging a tile does.
            state.openFolderId = null
            follow()
            try {
                val completed = drag(slop.id) { change ->
                    moved += change.positionChange()
                    change.consume()
                    follow()
                }
                val span = state.resizeSpan
                if (completed && span != null && (span.width != start.width || span.height != start.height)) {
                    currentOnSetSpan(span.width, span.height)
                }
            } finally {
                state.resizeId = null
                state.resizeSpan = null
            }
        }
    }
}

/**
 * Screen reader alternatives to the badges in [WidgetEditBadges]; empty for any other tile.
 * Instead of the handle there are actions that grow or shrink the tile by a cell, offered only
 * while the widget can go that way.
 */
@Composable
internal fun widgetCustomActions(
    tile: ResolvedTile,
    placement: Placement,
    columns: Int,
    cellDp: Float,
    onSetSpan: (width: Int, height: Int) -> Unit,
): List<CustomAccessibilityAction> {
    val widget = tile.spec.content as? TileContent.Widget ?: return emptyList()
    val context = LocalContext.current
    val info = remember(widget.appWidgetId) { WidgetTiles.providerInfo(context, widget.appWidgetId) }
    val limits = widgetSpanLimits(context, info, placement, columns, cellDp)
    val (w, h) = placement.width to placement.height
    return buildList {
        if (info != null && WidgetTiles.canReconfigure(info)) {
            add(CustomAccessibilityAction("Widget settings") { WidgetTiles.reconfigure(context, widget.appWidgetId); true })
        }
        val label = if (widget.transparent) "Show on tile" else "Hide tile background"
        add(CustomAccessibilityAction(label) { WidgetTiles.setTransparent(context, tile.spec.id, !widget.transparent); true })
        if (limits != null) {
            if (w < limits.maxWidth) add(CustomAccessibilityAction("Make wider") { onSetSpan(w + 1, h); true })
            if (w > limits.minWidth) add(CustomAccessibilityAction("Make narrower") { onSetSpan(w - 1, h); true })
            if (h < limits.maxHeight) add(CustomAccessibilityAction("Make taller") { onSetSpan(w, h + 1); true })
            if (h > limits.minHeight) add(CustomAccessibilityAction("Make shorter") { onSetSpan(w, h - 1); true })
        }
    }
}

private fun DrawScope.drawGear(color: Color, stroke: Float) {
    val c = center
    val r = size.minDimension
    drawCircle(color, r * 0.17f, c, style = Stroke(stroke))
    repeat(8) { i ->
        val angle = Math.toRadians(i * 45.0)
        val dir = Offset(Math.cos(angle).toFloat(), Math.sin(angle).toFloat())
        drawLine(color, c + dir * (r * 0.25f), c + dir * (r * 0.34f), stroke, StrokeCap.Round)
    }
}

/** A square: solid while the widget sits on the tile, an outline while it draws alone. */
private fun DrawScope.drawTileBackground(color: Color, stroke: Float, filled: Boolean) {
    val inset = size.minDimension * 0.3f
    val topLeft = Offset(inset, inset)
    val square = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
    if (filled) drawRect(color, topLeft, square) else drawRect(color, topLeft, square, style = Stroke(stroke))
}

/** A diagonal with an arrowhead at each end: drag to resize. */
internal fun DrawScope.drawResizeHandle(color: Color, stroke: Float) {
    val inset = size.minDimension * 0.3f
    val a = Offset(inset, inset)
    val b = Offset(size.width - inset, size.height - inset)
    val head = size.minDimension * 0.2f
    drawLine(color, a, b, stroke, StrokeCap.Round)
    drawLine(color, a, a + Offset(head, 0f), stroke, StrokeCap.Round)
    drawLine(color, a, a + Offset(0f, head), stroke, StrokeCap.Round)
    drawLine(color, b, b - Offset(head, 0f), stroke, StrokeCap.Round)
    drawLine(color, b, b - Offset(0f, head), stroke, StrokeCap.Round)
}
