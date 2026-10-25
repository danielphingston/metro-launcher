package io.github.danielphingston.metro

import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.search.rememberPullToSearch
import io.github.danielphingston.metro.ui.start.DragTracking
import io.github.danielphingston.metro.ui.start.GridPos
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.StartState
import io.github.danielphingston.metro.ui.start.TileHost
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Presses real [TileHost]s inside the stack the launcher puts them in (a pager that locks while
 * editing, the pull-to-search connection, the scrolling start column with its tap detector, and
 * [DragTracking]) and drives them with touch input on a clock the test steps by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TileDragTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val state = StartState(ScrollState(0))
    private var pickups = 0
    private var dropCompleted: Boolean? = null

    private fun app(label: String) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> ColorDrawable(0xFF444444.toInt()) },
    )

    private fun tile(id: Long, label: String) =
        ResolvedTile(TileSpec(id, TileSize.MEDIUM, TileContent.App(app(label).key)), app(label), label)

    /** "Phone" (id 1) at the top left and "Clock" (id 2) beside it, a 100dp cell each; the first is dragged. */
    private fun setGrid(inFolder: Boolean = false) {
        val icons = IconCache(context)
        val tiles = listOf(tile(1, "Phone"), tile(2, "Clock"))
        val placements = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MetroTheme(LauncherSettings()) {
                val pager = rememberPagerState { 2 }
                val pull = rememberPullToSearch(state.scroll, enabled = !state.editing) {}
                val cellPx = with(LocalDensity.current) { 100.dp.toPx() }
                HorizontalPager(pager, userScrollEnabled = !state.editing) { page ->
                    Box(Modifier.fillMaxSize().then(if (page == 0) Modifier.nestedScroll(pull) else Modifier)) {
                        if (page == 0) {
                            DragTracking(state, tiles, cellPx, 4)
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(state.scroll)
                                    .pointerInput(Unit) { detectTapGestures { if (state.editing) state.exitEdit() } },
                            ) {
                                Box(Modifier.size(400.dp, 400.dp)) {
                                    tiles.zip(placements).forEach { (tile, placement) ->
                                        androidx.compose.runtime.key(tile.spec.id) {
                                            TileHost(
                                                tile = tile,
                                                placement = placement,
                                                cellPx = cellPx,
                                                gapPx = 12f,
                                                cell = 100.dp,
                                                columns = 4,
                                                state = state,
                                                live = null,
                                                flipped = false,
                                                monochromeIcons = true,
                                                icons = icons,
                                                onUnpin = {},
                                                onResize = {},
                                                onSetSpan = { _, _ -> },
                                                onLaunch = {},
                                                onLongPress = { pickups++ },
                                                onRemoveFromFolder = if (inFolder) ({}) else null,
                                                onDrop = { completed -> dropCompleted = completed },
                                            )
                                        }
                                    }
                                }
                                Box(Modifier.height(3000.dp))
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
    }

    private val longPress get() = ViewConfiguration.getLongPressTimeout().toLong()

    private val phone get() = rule.onNodeWithContentDescription("Phone")

    /** Moves the held finger by [dx] px in [steps] steps, a frame apart. */
    private fun move(dx: Float, steps: Int = 6) = repeat(steps) {
        phone.performTouchInput { moveBy(Offset(dx / steps, 0f)) }
        rule.mainClock.advanceTimeBy(16)
    }

    /** Presses "Phone" and holds until it is picked up, without lifting. */
    private fun pickUp() {
        phone.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(longPress + 100)
    }

    @Test
    fun holdingUntilPickedUpThenMovingWithoutLiftingDragsTheTile() {
        setGrid()
        pickUp()
        assertEquals("picked up", 1, pickups)
        assertEquals(1L, state.selectedId)
        assertEquals(1L, state.dragId)
        val start = state.dragTopLeft

        phone.performTouchInput { moveBy(Offset(0f, 40f)) }
        rule.mainClock.advanceTimeBy(16)
        phone.performTouchInput { moveBy(Offset(0f, 40f)) }
        rule.mainClock.advanceTimeBy(16)
        phone.performTouchInput { moveBy(Offset(30f, 40f)) }
        rule.mainClock.advanceTimeBy(16)
        assertTrue("dragged ${state.dragTopLeft - start}", state.dragTopLeft.y - start.y >= 100f)
        assertEquals(1L, state.dragId)

        phone.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(null, state.dragId)
        assertEquals(true, dropCompleted)
    }

    @Test
    fun liftingAfterPickupAndThenDraggingStillWorks() {
        setGrid()
        pickUp()
        phone.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(null, state.dragId)
        assertEquals(1L, state.selectedId)

        phone.performTouchInput { down(center) }
        // The first moves only get past the touch slop; the tile follows after that.
        repeat(5) {
            phone.performTouchInput { moveBy(Offset(0f, 40f)) }
            rule.mainClock.advanceTimeBy(16)
        }
        assertEquals(1L, state.dragId)
        assertTrue("dragged ${state.dragTopLeft}", state.dragTopLeft.y > 60f)
        phone.performTouchInput { up() }
    }

    @Test
    fun aFolderChildIsDraggedTheSameWay() {
        setGrid(inFolder = true)
        pickUp()
        val start = state.dragTopLeft
        move(0f, 1)
        phone.performTouchInput { moveBy(Offset(0f, 60f)) }
        rule.mainClock.advanceTimeBy(16)
        phone.performTouchInput { moveBy(Offset(0f, 60f)) }
        rule.mainClock.advanceTimeBy(16)
        assertTrue("dragged ${state.dragTopLeft - start}", state.dragTopLeft.y - start.y >= 80f)
        phone.performTouchInput { up() }
    }

    @Test
    fun holdingOverAnotherTileNeverMakesAFolderAndTheDropJustMovesTheTile() {
        setGrid()
        pickUp()
        move(600f)
        // Far longer than any dwell: the tile is over the other one's middle and nothing is aimed at.
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(GridPos(2, 0), state.dragCell)
        assertEquals(GridPos(2, 0), state.preview?.to)
        assertEquals(1L, state.dragId)

        phone.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(true, dropCompleted)
        assertNull(state.dragId)
        assertNull(state.dragCell)
        // The drop keeps previewing the move until the stored layout arrives.
        assertEquals(GridPos(2, 0), state.preview?.to)
    }

    @Test
    fun theOtherTilesMakeRoomOnceTheDraggedOneHasRestedOnACell() {
        setGrid()
        pickUp()
        move(600f)
        rule.mainClock.advanceTimeBy(30)
        assertNotEquals("still passing over", GridPos(2, 0), state.preview?.to)
        rule.mainClock.advanceTimeBy(100)
        assertEquals(GridPos(2, 0), state.preview?.to)
    }

    @Test
    fun passingOverATileQuicklyDoesNotMakeTheOthersMove() {
        setGrid()
        pickUp()
        move(600f)
        move(-600f)
        rule.mainClock.advanceTimeBy(300)
        assertEquals(GridPos(0, 0), state.dragCell)
        assertEquals(GridPos(0, 0), state.preview?.to)
    }

    @Test
    fun tilesCannotBeDraggedOrPickedUpWhileGrouping() {
        setGrid()
        rule.runOnIdle { state.startGrouping(2) }
        rule.mainClock.advanceTimeBy(100)
        phone.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(longPress + 100)
        move(300f)
        assertEquals(0, pickups)
        assertNull(state.dragId)
        phone.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(16)
        assertEquals(2L, state.selectedId)
    }
}
