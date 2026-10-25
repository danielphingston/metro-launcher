package io.github.danielphingston.metro

import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileFolders
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.EditBadges
import io.github.danielphingston.metro.ui.start.GroupingBar
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.StartState
import io.github.danielphingston.metro.ui.start.TileHost
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Makes folders the way a person does: long-press a tile, tap its folder badge, tap the tiles to
 * add and the bar's "create folder". The real [TileHost]s, [EditBadges] and [GroupingBar] sit on
 * a [StartState] over a tile list that the test applies [TileFolders.groupIntoFolder] to, as the
 * start screen does through the repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class GroupingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val state = StartState(ScrollState(0))
    private var specs by mutableStateOf<List<TileSpec>>(emptyList())
    private var launches = 0
    private val longPress get() = ViewConfiguration.getLongPressTimeout().toLong()

    private fun app(label: String) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> ColorDrawable(0xFF444444.toInt()) },
    )

    private fun appSpec(id: Long, label: String, x: Int, y: Int) =
        TileSpec(id, TileSize.MEDIUM, TileContent.App(app(label).key), x, y)

    private fun resolve(spec: TileSpec): ResolvedTile = when (val content = spec.content) {
        is TileContent.Folder -> ResolvedTile(spec, null, content.name, content.children.map(::resolve))
        is TileContent.App -> {
            val app = app(content.app.packageName.substringAfterLast('.').replaceFirstChar(Char::uppercase))
            ResolvedTile(spec, app, app.label)
        }
        else -> ResolvedTile(spec, null, "weather")
    }

    /** Phone (1), Clock (2) and Maps (3) as medium tiles in a row, Camera (4) below them. */
    private fun setStart(vararg initial: TileSpec) {
        specs = initial.toList().ifEmpty {
            listOf(appSpec(1, "Phone", 0, 0), appSpec(2, "Clock", 2, 0), appSpec(3, "Maps", 4, 0), appSpec(4, "Camera", 0, 2))
        }
        val icons = IconCache(context)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MetroTheme(LauncherSettings()) {
                val cellPx = with(LocalDensity.current) { 100.dp.toPx() }
                val placements = TilePlacement.layout(specs, 6)
                val tiles = specs.map(::resolve)
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                when {
                                    state.grouping -> state.endGrouping()
                                    state.editing -> state.exitEdit()
                                }
                            }
                        },
                ) {
                    Box(Modifier.size(600.dp, 400.dp)) {
                        tiles.zip(placements).forEach { (tile, placement) ->
                            androidx.compose.runtime.key(tile.spec.id) {
                                TileHost(
                                    tile = tile,
                                    placement = placement,
                                    cellPx = cellPx,
                                    gapPx = 12f,
                                    cell = 100.dp,
                                    columns = 6,
                                    state = state,
                                    live = null,
                                    flipped = false,
                                    monochromeIcons = true,
                                    icons = icons,
                                    onUnpin = {},
                                    onResize = {},
                                    onSetSpan = { _, _ -> },
                                    onLaunch = { launches++ },
                                    onLongPress = {},
                                )
                            }
                        }
                        val selected = tiles.indexOfFirst { it.spec.id == state.selectedId }
                        if (state.editing && state.dragId == null && !state.grouping && selected >= 0) {
                            val spec = tiles[selected].spec
                            EditBadges(
                                placement = placements[selected],
                                cellPx = cellPx,
                                gapPx = 12f,
                                onUnpin = {},
                                onResize = {},
                                onGroup = if (TileFolders.canGroup(spec)) ({ state.startGrouping(spec.id) }) else null,
                                groupLabel = if (spec.content is TileContent.Folder) "add to folder" else "group into folder",
                            )
                        }
                    }
                    if (state.grouping) {
                        GroupingBar(
                            anchorIsFolder = specs.firstOrNull { it.id == state.groupingAnchorId }?.content is TileContent.Folder,
                            count = state.groupingSelection.size,
                            onConfirm = {
                                val grouped = TileFolders.groupIntoFolder(specs, state.groupingAnchorId!!, state.groupingSelection, 100)
                                if (grouped != null) specs = TilePlacement.settle(grouped.tiles, 6, grouped.folderId)
                                state.endGrouping()
                                grouped?.let { state.select(it.folderId) }
                            },
                            onCancel = state::endGrouping,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        settle()
    }

    /** Lets writes made by a gesture or the test reach the composition, then plays out the animations. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(300)
    }

    private fun tile(label: String) = rule.onNodeWithContentDescription(label)

    private fun longPress(label: String) {
        tile(label).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(longPress + 100)
        tile(label).performTouchInput { up() }
        settle()
    }

    private fun tap(label: String) {
        tile(label).performTouchInput { click(center) }
        settle()
    }

    private fun tapBadge(label: String) {
        rule.onNodeWithContentDescription(label).performTouchInput { click(center) }
        settle()
    }

    private fun tapBar(text: String) {
        rule.onNode(hasText(text, substring = true)).performTouchInput { click(center) }
        settle()
    }

    private fun children(spec: TileSpec) = (spec.content as TileContent.Folder).children.map { it.id }

    @Test
    fun longPressTheFolderBadgeThenTwoTilesAndCreateFolderMakesAFolderOfThreeInOrder() {
        setStart()
        longPress("Phone")
        assertTrue(state.editing)
        assertEquals(1L, state.selectedId)

        tapBadge("group into folder")
        assertEquals(1L, state.groupingAnchorId)
        tap("Maps")
        tap("Clock")
        assertEquals(listOf(3L, 2L), state.groupingSelection)
        tapBar("create folder (2)")

        val folder = specs.single { it.content is TileContent.Folder }
        assertEquals(listOf(1L, 3L, 2L), children(folder))
        assertEquals(100L, folder.id)
        // Camera is all that is left beside it, risen to the top.
        assertEquals(listOf(100L, 4L), specs.map { it.id })
        assertFalse(state.grouping)
        assertTrue(state.editing)
        assertEquals(100L, state.selectedId)
        assertEquals("folder", (folder.content as TileContent.Folder).name)
        assertEquals(0, launches)
    }

    @Test
    fun anExistingFolderTakesTheTilesPicked() {
        val folder = TileSpec(
            5, TileSize.MEDIUM, TileContent.Folder("games", listOf(appSpec(6, "Chess", TileSpec.UNPLACED, TileSpec.UNPLACED), appSpec(7, "Cards", TileSpec.UNPLACED, TileSpec.UNPLACED))), 0, 0,
        )
        setStart(folder, appSpec(1, "Phone", 2, 0), appSpec(2, "Clock", 4, 0))
        longPress("games, folder, 2 items")
        tapBadge("add to folder")
        tap("Clock")
        tap("Phone")
        rule.onNode(hasText("add to folder (2)")).assertIsEnabled()
        tapBar("add to folder (2)")

        assertEquals(listOf(5L), specs.map { it.id })
        assertEquals(listOf(6L, 7L, 2L, 1L), children(specs.single()))
        assertEquals(5L, state.selectedId)
        assertFalse(state.grouping)
    }

    @Test
    fun tappingATileAgainTakesItOutAndTheBarWaitsForAPick() {
        setStart()
        longPress("Phone")
        tapBadge("group into folder")
        rule.onNode(hasText("create folder (0)")).assertIsNotEnabled()
        tap("Clock")
        rule.onNode(hasText("create folder (1)")).assertIsEnabled()
        tap("Clock")
        assertEquals(emptyList<Long>(), state.groupingSelection)
        rule.onNode(hasText("create folder (0)")).assertIsNotEnabled()
        // A disabled bar does nothing.
        tapBar("create folder (0)")
        assertTrue(state.grouping)
        assertTrue(specs.none { it.content is TileContent.Folder })
    }

    @Test
    fun theAnchorAndTilesThatCannotJoinDoNotRespondToTaps() {
        val weather = TileSpec(8, TileSize.MEDIUM, TileContent.Weather, 4, 0)
        setStart(appSpec(1, "Phone", 0, 0), appSpec(2, "Clock", 2, 0), weather)
        state.startGrouping(1)
        settle()
        tap("Phone")
        assertEquals(emptyList<Long>(), state.groupingSelection)
        assertEquals(1L, state.groupingAnchorId)
        assertEquals(0, launches)
    }

    @Test
    fun cancelBackAndTappingEmptySpaceLeaveGroupingButStayInEditMode() {
        setStart()
        longPress("Phone")
        tapBadge("group into folder")
        tap("Clock")
        tapBar("cancel")
        assertFalse(state.grouping)
        assertTrue(state.editing)
        assertEquals(1L, state.selectedId)
        assertEquals(emptyList<Long>(), state.groupingSelection)

        tapBadge("group into folder")
        rule.runOnIdle { assertTrue(state.dismissFlyouts()) }
        settle()
        assertFalse(state.grouping)
        assertTrue(state.editing)

        tapBadge("group into folder")
        rule.onRoot().performTouchInput { click(Offset(width / 2f, height - 40f)) }
        settle()
        assertFalse(state.grouping)
        assertTrue(state.editing)

        // Home and leaving edit mode end it too.
        tapBadge("group into folder")
        rule.runOnIdle { state.exitEdit() }
        settle()
        assertFalse(state.grouping)
        assertFalse(state.editing)
        rule.runOnIdle { state.startGrouping(1) }
        rule.runOnIdle { assertTrue(state.dismissOverlays()) }
        assertFalse(state.grouping)
    }

    @Test
    fun tilesAnnounceWhetherTheyAreSelectedAndToggleOnTheirClickAction() {
        setStart()
        // The accessibility action starts grouping from a tile that is not even in edit mode.
        val actions = tile("Phone").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Group into folder…" }.action() }
        settle()
        assertEquals(1L, state.groupingAnchorId)
        assertTrue(state.editing)

        tile("Clock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not selected"))
        tile("Clock").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        tile("Clock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
        assertEquals(listOf(2L), state.groupingSelection)
        assertNull(state.dragId)
    }

    @Test
    fun theBadgeIsOfferedOnGroupableTilesOnly() {
        val weather = TileSpec(8, TileSize.MEDIUM, TileContent.Weather, 0, 0)
        setStart(weather, appSpec(1, "Phone", 2, 0))
        longPress("weather")
        assertTrue(rule.onAllNodesWithContentDescription("group into folder").fetchSemanticsNodes().isEmpty())
    }
}
