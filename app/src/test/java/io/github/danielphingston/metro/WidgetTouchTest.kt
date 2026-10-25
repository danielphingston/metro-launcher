package io.github.danielphingston.metro

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.WidgetFrame
import io.github.danielphingston.metro.ui.tiles.WidgetTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs

/** A stand-in for a widget's content that counts what it is sent and can scroll like a list. */
private class TouchProbe(context: Context, private val scrolls: Boolean, private val greedy: Boolean = false) :
    ViewGroup(context) {
    var downs = 0
    var cancels = 0
    var clicks = 0
    var scrolled = 0f
    private var startY = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A widget that wants every gesture to itself says so at once.
                if (greedy) parent.requestDisallowInterceptTouchEvent(true)
                downs++
                startY = e.y
                lastY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Like a ListView, claim the gesture once the finger has moved.
                if (!dragging && abs(e.y - startY) > slop) {
                    dragging = true
                    if (scrolls) parent.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) scrolled += lastY - e.y
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragging) clicks++
            MotionEvent.ACTION_CANCEL -> cancels++
        }
        return true
    }
}

/**
 * Touches through the same Compose modifiers a tile has on the start screen (a long-press
 * detector, a tap detector, merged semantics for other tiles, all inside the scrolling start
 * column) into a hosted widget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class WidgetTouchTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val widgetId = 11
    private val startScroll = ScrollState(0)
    private var longPresses = 0
    private var widgetLongPresses = 0
    private var editing by mutableStateOf(false)
    private lateinit var probe: TouchProbe

    @Before
    fun bindWidget() {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example", "com.example.Widget")
            minWidth = 200
            minHeight = 100
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
    }

    /** The start column with one tile of [width] by [height] dp, built like `TileHost`. */
    private fun setTile(width: Int = 300, height: Int = 200, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            MetroTheme(LauncherSettings()) {
                Column(Modifier.fillMaxSize().verticalScroll(startScroll)) {
                    Box(
                        Modifier
                            .size(width.dp, height.dp)
                            .testTag("tile")
                            .semantics(mergeDescendants = false) { contentDescription = "widget" }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    awaitLongPressOrCancellation(down.id)?.also { longPresses++ }
                                }
                            }
                            .pointerInput(Unit) { detectTapGestures { } },
                    ) { content() }
                    Box(Modifier.height(3000.dp))
                }
            }
        }
    }

    /** A hosted view in a [WidgetFrame] as the tile body, like `WidgetTile` builds it. */
    private fun setProbe(scrolls: Boolean, greedy: Boolean = false) = setTile {
        AndroidView(
            factory = { ctx ->
                probe = TouchProbe(ctx, scrolls, greedy)
                WidgetFrame(ctx, probe) { widgetLongPresses++ }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** What the widget's button sends. */
    private fun tapIntent() = android.app.PendingIntent.getActivity(
        context, 0, android.content.Intent("io.github.danielphingston.metro.TAP"), android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    /** How many times that was sent so far. */
    private fun taps(): Int {
        var count = 0
        while (shadowOf(rule.activity).nextStartedActivity?.action == "io.github.danielphingston.metro.TAP") count++
        return count
    }

    private fun hostView(): AppWidgetHostView {
        fun find(view: View): AppWidgetHostView? {
            if (view is AppWidgetHostView) return view
            if (view !is ViewGroup) return null
            return (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        }
        return find(rule.activity.window.decorView)!!
    }

    @Test
    fun aTapOnAWidgetReachesItsRemoteViewsButton() {
        setTile {
            WidgetTile(widgetTile(), 96.dp, editing = editing)
        }
        val host = hostView()
        rule.runOnIdle {
            val views = RemoteViews("android", android.R.layout.simple_list_item_1)
            views.setOnClickPendingIntent(android.R.id.text1, tapIntent())
            host.updateAppWidget(views)
        }

        rule.waitForIdle()
        rule.onNodeWithTag("tile").performTouchInput { click() }
        assertEquals(1, taps())
    }

    @Test
    fun theWidgetGetsNoTouchesWhileEditing() {
        editing = true
        setTile { WidgetTile(widgetTile(), 96.dp, editing = editing) }
        val host = hostView()
        rule.runOnIdle {
            val views = RemoteViews("android", android.R.layout.simple_list_item_1)
            views.setOnClickPendingIntent(android.R.id.text1, tapIntent())
            host.updateAppWidget(views)
        }
        rule.waitForIdle()

        rule.onNodeWithTag("tile").performTouchInput { click() }
        assertEquals(0, taps())
    }

    @Test
    fun theWidgetIsToldTheTilesRealSize() {
        setTile(width = 300, height = 200) { WidgetTile(widgetTile(), 96.dp) }
        rule.waitForIdle()
        val frame = hostView().parent as WidgetFrame
        assertEquals(SizeF(300f, 200f), frame.appliedSize)
        // The widget fills its cell: the host view's default padding is dropped.
        hostView().setPadding(12, 12, 12, 12)
        assertEquals(0, hostView().paddingLeft)
    }

    @Test
    fun aTapReachesAViewInTheFrame() {
        setProbe(scrolls = true)
        rule.onNodeWithTag("tile").performTouchInput { click() }
        assertEquals(1, probe.clicks)
        assertEquals(0, longPresses)
    }

    @Test
    fun aScrollInsideTheWidgetDoesNotScrollTheStartScreen() {
        setProbe(scrolls = true)
        rule.onNodeWithTag("tile").performTouchInput { swipeUp() }
        assertTrue("widget scrolled ${probe.scrolled}", probe.scrolled > 100f)
        assertEquals(0, startScroll.value)
        assertEquals(0, probe.cancels)
        assertEquals(0, longPresses)
    }

    @Test
    fun aDragOverAWidgetThatDoesNotScrollScrollsTheStartScreen() {
        setProbe(scrolls = false)
        rule.onNodeWithTag("tile").performTouchInput { swipeUp() }
        assertTrue("start scrolled ${startScroll.value}", startScroll.value > 100)
        assertEquals(1, probe.cancels)
        assertEquals(0, longPresses)
    }

    @Test
    fun aLongPressOnAWidgetThatTookTheTouchIsStillSeenByTheTile() {
        setProbe(scrolls = true)
        rule.onNodeWithTag("tile").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        assertEquals(1, longPresses)
    }

    private fun widgetTile() = ResolvedTile(
        TileSpec(1, TileSize.WIDE, TileContent.Widget(widgetId, "com.example/.Widget", 0)), null, "widget",
    )
}

/** The frame's own long-press timer, which needs a real clock, so it runs on a plain activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FrameLongPressTest {
    private lateinit var activity: Activity
    private var longPresses = 0

    private fun frameOf(probe: TouchProbe): WidgetFrame {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = WidgetFrame(activity, probe) { longPresses++ }
        activity.setContentView(frame)
        pass(0)
        return frame
    }

    private fun pass(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun send(frame: View, action: Int, time: Long, x: Float = 20f, y: Float = 20f) {
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        frame.dispatchTouchEvent(event)
        event.recycle()
    }

    private var downTime = 0L

    private fun down(frame: View) {
        downTime = SystemClock.uptimeMillis()
        send(frame, MotionEvent.ACTION_DOWN, downTime)
    }

    @Test
    fun aHeldTouchOnAWidgetThatTookItCancelsTheWidgetAndReports() {
        val probe = TouchProbe(ApplicationProvider.getApplicationContext(), scrolls = true)
        val frame = frameOf(probe)
        down(frame)
        pass(ViewConfiguration.getLongPressTimeout() + 200L)
        assertEquals(1, longPresses)
        assertEquals(1, probe.cancels)
        // The rest of the gesture is swallowed: lifting doesn't click the widget.
        send(frame, MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        assertEquals(0, probe.clicks)
    }

    @Test
    fun aWidgetThatClaimsTheGestureAtOnceStillGetsItsLongPressFound() {
        val probe = TouchProbe(ApplicationProvider.getApplicationContext(), scrolls = true, greedy = true)
        val frame = frameOf(probe)
        down(frame)
        pass(ViewConfiguration.getLongPressTimeout() + 200L)
        assertEquals(1, longPresses)
        assertEquals(1, probe.cancels)
    }

    @Test
    fun releasingBeforeTheLongPressTimeReportsNothing() {
        val probe = TouchProbe(ApplicationProvider.getApplicationContext(), scrolls = true)
        val frame = frameOf(probe)
        down(frame)
        pass(100)
        send(frame, MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        pass(ViewConfiguration.getLongPressTimeout() + 200L)
        assertEquals(0, longPresses)
        assertEquals(1, probe.clicks)
    }

    @Test
    fun aScrollTheWidgetShieldedFromTheFrameNeverCountsAsALongPress() {
        // Once the widget scrolls it asks that no parent intercepts; the frame still must drop its timer.
        val probe = TouchProbe(ApplicationProvider.getApplicationContext(), scrolls = true)
        val frame = frameOf(probe)
        down(frame)
        send(frame, MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), y = 120f)
        send(frame, MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), y = 160f)
        pass(ViewConfiguration.getLongPressTimeout() + 200L)
        assertEquals(0, longPresses)
        assertEquals(0, probe.cancels)
        assertTrue(probe.scrolled != 0f)
    }

    @Test
    fun aWidgetThatIgnoresTheTouchIsLeftToCompose() {
        val ignoring = object : ViewGroup(ApplicationProvider.getApplicationContext()) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
            override fun onTouchEvent(event: MotionEvent) = false
        }
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = WidgetFrame(activity, ignoring) { longPresses++ }
        activity.setContentView(frame)
        down(frame)
        pass(ViewConfiguration.getLongPressTimeout() + 200L)
        assertEquals(0, longPresses)
    }
}
