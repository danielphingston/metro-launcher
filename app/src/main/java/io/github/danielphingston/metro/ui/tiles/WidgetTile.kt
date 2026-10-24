package io.github.danielphingston.metro.ui.tiles

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.theme.Metro
import kotlin.math.hypot

/** Hosts Android app widgets inside tiles. One [AppWidgetHost] serves the whole app. */
object WidgetTiles {
    /** Identifies this launcher to the system's widget service; allocated ids belong to it. */
    private const val HOST_ID = 0x4D455452

    /** Matches the gap between start screen tiles. */
    private val TileGap = 8.dp

    private var host: AppWidgetHost? = null

    /** Widget tiles currently on a STARTED screen. Main thread only. */
    private var listeners = 0

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    /** Frees the app widget id when its tile is unpinned. */
    fun release(context: Context, appWidgetId: Int) = host(context).deleteAppWidgetId(appWidgetId)

    /** The widget's provider, or null if the id is invalid or the provider is gone. */
    internal fun providerInfo(context: Context, appWidgetId: Int): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)

    /**
     * Widgets only update while the host listens, which costs a binder callback per update.
     * So it listens while at least one widget tile is on a STARTED screen.
     */
    internal fun startListening(context: Context) {
        if (listeners++ == 0) host(context).startListening()
    }

    internal fun stopListening(context: Context) {
        if (listeners > 0 && --listeners == 0) host(context).stopListening()
    }

    /** The size a tile of [size] gives its widget. */
    internal fun widgetSize(cell: Dp, size: TileSize): SizeF {
        val width = cell * size.width - TileGap
        val height = cell * size.height - TileGap
        return SizeF(width.value, height.value)
    }

    /** The widget's size in launcher cells, from its declared target or else its minimum size. */
    internal fun cellsOf(context: Context, info: AppWidgetProviderInfo): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        fun estimate(px: Int) = ((px / density + 30) / 70).toInt().coerceAtLeast(1)
        val cols = info.targetCellWidth.takeIf { it > 0 } ?: estimate(info.minWidth)
        val rows = info.targetCellHeight.takeIf { it > 0 } ?: estimate(info.minHeight)
        return cols to rows
    }

    /** Wide for most widgets, large for tall ones, medium for small ones. */
    internal fun suggestedSize(cols: Int, rows: Int): TileSize = when {
        rows > 2 -> TileSize.LARGE
        cols <= 2 && rows <= 2 -> TileSize.MEDIUM
        else -> TileSize.WIDE
    }
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

    val size = WidgetTiles.widgetSize(cellSize, tile.spec.size)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    Box(Modifier.fillMaxSize().background(Metro.colors.accent)) {
        AndroidView(
            factory = { ctx ->
                val hostView = WidgetTiles.host(ctx).createView(ctx, widget.appWidgetId, info)
                WidgetFrame(ctx, hostView) { currentOnLongPress() }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.hostView.updateAppWidgetSize(Bundle(), listOf(size)) },
        )
        if (editing) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
    }
}

/**
 * Holds the widget's host view and notices a long-press that the widget consumed (any widget
 * with a click action does). The widget is cancelled and the rest of the gesture is swallowed.
 * When the widget ignores the touch, Compose still sees it and detects the long-press itself,
 * so nothing is reported here.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class WidgetFrame(
    context: Context,
    val hostView: AppWidgetHostView,
    private val onLongPress: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var widgetHandledDown = false
    private var longPressed = false

    private val longPress = Runnable {
        if (!widgetHandledDown) return@Runnable
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        // Only the widget can accept a DOWN: onTouchEvent below declines it.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) widgetHandledDown = handled
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return longPressed
    }

    /** Declines a DOWN (see [dispatchTouchEvent]); after a long-press, swallows the rest of the gesture. */
    override fun onTouchEvent(event: MotionEvent): Boolean = longPressed

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
