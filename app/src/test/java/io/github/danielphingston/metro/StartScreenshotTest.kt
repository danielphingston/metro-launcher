package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarEvent
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.applist.AppListContent
import io.github.danielphingston.metro.ui.applist.appListRows
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.MetroSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.settings.AccentGrid
import io.github.danielphingston.metro.ui.settings.Section
import io.github.danielphingston.metro.ui.start.EditBadge
import io.github.danielphingston.metro.ui.start.EditBadges
import io.github.danielphingston.metro.ui.start.FolderLayout
import io.github.danielphingston.metro.ui.start.FolderRegion
import io.github.danielphingston.metro.ui.start.GroupingBar
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.ResizeGhost
import io.github.danielphingston.metro.ui.start.StartState
import io.github.danielphingston.metro.ui.start.TileFace
import io.github.danielphingston.metro.ui.start.TileHost
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.start.TurnstilePhase
import io.github.danielphingston.metro.ui.start.TurnstileState
import io.github.danielphingston.metro.ui.start.turnstile
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import io.github.danielphingston.metro.ui.tiles.FolderTile
import io.github.danielphingston.metro.ui.tiles.drawResizeHandle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Renders a representative start screen, app list and settings controls on the JVM so the look
 * can be reviewed without a device. Output: app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class StartScreenshotTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 24, 10, 42) }.timeInMillis
    private val hour = 3_600_000L

    private data class Sample(val app: AppInfo, val size: TileSize, val live: LiveContent?, val flipped: Boolean = false)

    private fun samples(): List<Sample> {
        val messages = AppNotifications(
            3,
            listOf(NotificationPreview("Mom", "Are you coming for dinner tonight? Bring the cake!", now)),
        )
        val mail = AppNotifications(
            12,
            listOf(NotificationPreview("Contoso Travel", "Your flight to Seattle is confirmed for Friday", now)),
        )
        val events = listOf(
            CalendarEvent("Design review", now + hour, now + 2 * hour, false, "Room 4"),
            CalendarEvent("Dentist", now + 26 * hour, now + 27 * hour, false, null),
        )
        return listOf(
            Sample(app("Phone"), TileSize.MEDIUM, null),
            Sample(app("Messages"), TileSize.MEDIUM, LiveContent.Notifications(messages, true)),
            Sample(app("Outlook"), TileSize.WIDE, LiveContent.Notifications(mail, true), flipped = true),
            Sample(app("Calendar"), TileSize.WIDE, LiveContent.Calendar(now, events, false)),
            Sample(app("Clock"), TileSize.MEDIUM, LiveContent.Clock(ClockState(now, now + 20 * hour), false)),
            Sample(app("Camera"), TileSize.SMALL, null),
            Sample(app("Store"), TileSize.SMALL, LiveContent.Notifications(AppNotifications(2, emptyList()), true)),
            Sample(app("Settings"), TileSize.SMALL, null),
            Sample(app("Maps"), TileSize.SMALL, null),
            Sample(app("WhatsApp"), TileSize.WIDE, LiveContent.Notifications(messages, true), flipped = true),
            Sample(app("Photos"), TileSize.MEDIUM, null),
            Sample(app("Music"), TileSize.MEDIUM, null),
        )
    }

    @Test
    fun startDark() = capture("start_dark", ThemeMode.DARK, 4)

    @Test
    fun startLight() = capture("start_light", ThemeMode.LIGHT, 4, accent = 0xFFE51400.toInt())

    @Test
    fun startMoreTiles() = capture("start_more_tiles", ThemeMode.DARK, 6, accent = 0xFF008A00.toInt())

    /** A frozen frame of the turnstile exit: the left column is gone, the right is mid-swing. */
    @Test
    fun startTurnstile() {
        val frozen = TurnstileState().apply { seek(TurnstilePhase.OUT, 110f) }
        capture("start_turnstile", ThemeMode.DARK, 4, turnstile = frozen)
    }

    @Test
    fun startEditing() = capture("start_editing", ThemeMode.DARK, 4, editing = true)

    /** A selected tile with the outline of a bigger footprint, as shown while a widget's handle is dragged. */
    @Test
    fun startResizing() = capture("start_resizing", ThemeMode.DARK, 4, editing = true, resizing = true)

    @Test
    fun startGrouping() = captureRoboImage("src/test/screenshots/start_grouping.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings(columns = 4)) { GroupingPreview() }
    }

    @Test
    fun startPhotoTiles() = capture("start_photo_tiles", ThemeMode.DARK, 4, background = StartBackground.PHOTO_TILES)

    @Test
    fun startPhotoTilesLight() = capture(
        "start_photo_tiles_light", ThemeMode.LIGHT, 4, accent = 0xFFD80073.toInt(), background = StartBackground.PHOTO_TILES,
    )

    @Test
    fun startPhotoFull() = capture("start_photo_full", ThemeMode.DARK, 4, background = StartBackground.PHOTO_FULL)

    @Test
    fun startSolidTranslucent() = capture("start_solid_translucent", ThemeMode.DARK, 4, transparency = 100)

    @Test
    fun startFolderClosed() = captureFolders("start_folder_closed", ThemeMode.DARK, openFolder = null)

    @Test
    fun startFolderOpen() = captureFolders("start_folder_open", ThemeMode.DARK, openFolder = GAMES_ID)

    @Test
    fun startFolderOpenLight() =
        captureFolders("start_folder_open_light", ThemeMode.LIGHT, openFolder = GAMES_ID, accent = 0xFF0050EF.toInt())

    @Test
    fun startFolderOpenBesideTallTile() =
        captureFolders("start_folder_open_tall", ThemeMode.DARK, openFolder = GAMES_ID, tallTile = true)

    @Test
    fun appList() = captureRoboImage("src/test/screenshots/app_list.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings()) { AppListPreview() }
    }

    @Test
    fun appListLight() = captureRoboImage("src/test/screenshots/app_list_light.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings(theme = ThemeMode.LIGHT, accent = 0xFF6A00FF.toInt())) { AppListPreview() }
    }

    /** A tablet in landscape: the list keeps a phone's width in the middle. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun appListTablet() = captureRoboImage("src/test/screenshots/app_list_tablet.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings()) { AppListPreview() }
    }

    @Test
    fun menuFlyout() = captureRoboImage("src/test/screenshots/menu_flyout.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings()) {
            Box(Modifier.fillMaxSize()) {
                StartPreview(samples(), 4, StartBackdrop.Solid)
                MetroFlyout(onDismiss = {}) {
                    MetroMenu(
                        listOf("weather" to {}, "photos" to {}, "people" to {}, "widgets" to {}),
                        Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    fun settingsControls() = captureRoboImage("src/test/screenshots/settings_controls.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings(accent = 0xFF0050EF.toInt())) { SettingsPreview() }
    }

    @Test
    fun settingsControlsLight() = captureRoboImage("src/test/screenshots/settings_controls_light.png", roborazziOptions = ScreenshotOptions) {
        MetroTheme(LauncherSettings(theme = ThemeMode.LIGHT, accent = 0xFFFA6800.toInt())) { SettingsPreview() }
    }

    private fun capture(
        name: String,
        theme: ThemeMode,
        columns: Int,
        accent: Int = LauncherSettings.DEFAULT_ACCENT,
        background: StartBackground = StartBackground.SOLID,
        transparency: Int? = null,
        editing: Boolean = false,
        turnstile: TurnstileState? = null,
        resizing: Boolean = false,
    ) {
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = theme, accent = accent, columns = columns)) {
                val backdrop = remember {
                    val settings = LauncherSettings(background = background, tileTransparency = transparency)
                    StartBackdrop.of(settings, if (settings.showsPhoto) photo() else null) { 0f }
                }
                StartPreview(samples(), columns, backdrop, editing, turnstile, resizing)
            }
        }
    }

    private fun captureFolders(
        name: String,
        theme: ThemeMode,
        openFolder: Long?,
        accent: Int = LauncherSettings.DEFAULT_ACCENT,
        tallTile: Boolean = false,
    ) {
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = theme, accent = accent, columns = 4)) {
                FolderPreview(openFolder, tallTile)
            }
        }
    }

    private val folderIcons by lazy { IconCache(RuntimeEnvironment.getApplication()) }

    /**
     * Icons load on a background thread and the capture doesn't wait for it, so render the ones the
     * folder screens need up front: the sizes tiles ask for at this cell size.
     */
    private fun warmIcons(icons: IconCache, tiles: List<ResolvedTile>) {
        val cell = (395 + 4) / 4f
        for (tile in tiles) for (base in listOf(32f, 64f, 40f, 20f)) {
            val px = (base * cell / 96f * 3f).roundToInt()
            tile.app?.let { icons.load(it, px, true) }
        }
    }

    private fun appTile(id: Long, label: String, size: TileSize): ResolvedTile {
        val app = app(label)
        return ResolvedTile(TileSpec(id, size, TileContent.App(app.key)), app, label)
    }

    private fun folderTile(id: Long, name: String, size: TileSize, children: List<ResolvedTile>) =
        ResolvedTile(TileSpec(id, size, TileContent.Folder(name, children.map { it.spec })), null, name, children)

    /** A sunset behind hills: a bright sky, dark foreground and one strong hue. */
    private fun photo(): ImageBitmap {
        val w = 1233
        val h = (2673 * (1 + BackgroundPhoto.HEADROOM)).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF0B1D51.toInt(), 0xFF5B3A8C.toInt(), 0xFFE8644A.toInt(), 0xFFF6B352.toInt()),
            floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFE9B0.toInt()
        canvas.drawCircle(w * 0.7f, h * 0.62f, w * 0.14f, paint)
        listOf(0xFF3B2A5A.toInt(), 0xFF22183A.toInt(), 0xFF120C22.toInt()).forEachIndexed { i, color ->
            paint.color = color
            val base = h * (0.7f + 0.1f * i)
            val hill = Path()
            hill.moveTo(0f, h.toFloat())
            hill.lineTo(0f, base)
            var x = 0f
            while (x < w) {
                hill.quadTo(x + w * 0.2f, base - h * (0.05f + 0.02f * i), x + w * 0.4f, base)
                x += w * 0.4f
            }
            hill.lineTo(w.toFloat(), h.toFloat())
            hill.close()
            canvas.drawPath(hill, paint)
        }
        return bitmap.asImageBitmap()
    }

    @Composable
    private fun StartPreview(
        samples: List<Sample>,
        columns: Int,
        backdrop: StartBackdrop,
        editing: Boolean = false,
        turnstile: TurnstileState? = null,
        resizing: Boolean = false,
    ) {
        val context = LocalContext.current
        val icons = remember { IconCache(context) }
        val density = LocalDensity.current
        val bounds = remember { mutableStateMapOf<Int, Rect>() }
        CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                    val gap = 4.dp
                    val cell = (maxWidth + gap) / columns
                    // New tiles are appended in order, which is what pinning them one after another does.
                    val placements = TilePlacement.layout(
                        samples.mapIndexed { i, sample -> TileSpec(i + 1L, sample.size, TileContent.Weather) }, columns,
                    )
                    samples.zip(placements).forEachIndexed { index, (sample, p) ->
                        val deemphasised = editing && index != 1
                        Box(
                            Modifier
                                .offset(cell * p.x, cell * p.y)
                                .size(cell * p.width - gap, cell * p.height - gap)
                                .graphicsLayer {
                                    scaleX = if (deemphasised) 0.94f else 1f
                                    scaleY = scaleX
                                    alpha = if (deemphasised) 0.6f else 1f
                                }
                                .onGloballyPositioned { bounds[index] = it.boundsInRoot() }
                                .then(if (turnstile != null) Modifier.turnstile(turnstile, index) { bounds[index] } else Modifier),
                        ) {
                            TileFace(sample.app, sample.size, sample.live, sample.flipped, true, icons, cell)
                        }
                    }
                    if (editing) {
                        val cellPx = with(density) { cell.toPx() }
                        val gapPx = with(density) { gap.toPx() }
                        if (resizing) {
                            // A widget tile has a drag handle where other tiles have the resize badge.
                            val badge = placements[1]
                            EditBadges(badge, cellPx, gapPx, {}, null)
                            EditBadge(
                                label = "resize handle",
                                onClick = null,
                                modifier = Modifier.offset(
                                    cell * (badge.x + badge.width) - gap - 25.5.dp, cell * (badge.y + badge.height) - gap - 21.dp,
                                ),
                            ) { color, stroke -> drawResizeHandle(color, stroke) }
                            ResizeGhost(Placement(badge.x, badge.y, badge.width, badge.height + 1), cellPx, gapPx)
                        } else {
                            EditBadges(placements[1], cellPx, gapPx, {}, {}, onGroup = {})
                        }
                    }
                }
            }
        }
    }

    /**
     * Grouping mode on the real tiles: "Messages" is the anchor, "Phone" and "Maps" are picked, the
     * calendar tile is a weather tile as a stand-in for any tile that can't be grouped.
     */
    @Composable
    private fun GroupingPreview() {
        val context = LocalContext.current
        val icons = remember { IconCache(context) }
        val density = LocalDensity.current
        val columns = 4
        val samples = samples()
        val tiles = samples.mapIndexed { index, sample ->
            val spec = if (sample.app.label == "Calendar") {
                TileSpec(index + 1L, sample.size, TileContent.Weather)
            } else {
                TileSpec(index + 1L, sample.size, TileContent.App(sample.app.key))
            }
            ResolvedTile(spec, sample.app, sample.app.label)
        }
        val placements = TilePlacement.layout(tiles.map { it.spec }, columns)
        val state = remember {
            StartState(ScrollState(0)).apply {
                startGrouping(2)
                toggleGrouped(1)
                toggleGrouped(11)
            }
        }
        val backdrop = StartBackdrop.Solid
        CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                    val gap = 4.dp
                    val cell = (maxWidth + gap) / columns
                    val cellPx = with(density) { cell.toPx() }
                    tiles.zip(placements).forEachIndexed { index, (tile, p) ->
                        TileHost(
                            tile = tile,
                            placement = p,
                            cellPx = cellPx,
                            gapPx = with(density) { gap.toPx() },
                            cell = cell,
                            columns = columns,
                            state = state,
                            live = samples[index].live,
                            flipped = samples[index].flipped,
                            monochromeIcons = true,
                            icons = icons,
                            onUnpin = {},
                            onResize = {},
                            onSetSpan = { _, _ -> },
                            onLaunch = {},
                            onLongPress = {},
                        )
                    }
                }
                GroupingBar(
                    anchorIsFolder = false,
                    count = state.groupingSelection.size,
                    onConfirm = {},
                    onCancel = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    /**
     * The start screen with two folders; [openFolder] expands one of them the way the real screen
     * does, by laying the tiles out with [FolderLayout] and drawing the children in a [FolderRegion].
     */
    @Composable
    private fun FolderPreview(openFolder: Long?, tallTile: Boolean) {
        val icons = folderIcons
        val columns = 4
        val unread = LiveContent.Notifications(AppNotifications(5, emptyList()), true)
        val games = folderTile(
            GAMES_ID, "games", TileSize.MEDIUM,
            listOf(
                appTile(11, "Xbox", TileSize.MEDIUM), appTile(12, "Chess", TileSize.MEDIUM), appTile(13, "Cards", TileSize.SMALL),
                appTile(14, "Racing", TileSize.SMALL), appTile(15, "Puzzle", TileSize.SMALL), appTile(16, "Poker", TileSize.SMALL),
                appTile(17, "Trivia", TileSize.WIDE), appTile(18, "Arcade", TileSize.MEDIUM),
            ).let { if (tallTile) it.take(4) else it },
        )
        val work = folderTile(
            WORK_ID, "work", TileSize.WIDE,
            listOf("Docs", "Sheets", "Slides", "Drive", "Meet", "Notes", "Tasks", "Keep", "Gmail", "Maps", "Files")
                .mapIndexed { i, label -> appTile(30L + i, label, TileSize.MEDIUM) },
        )
        val phone = appTile(1, "Phone", TileSize.MEDIUM)
        val tiles = listOf(
            // With [tallTile], the folder sits beside a tile twice its height, which moves below the open region.
            if (tallTile) phone.copy(spec = phone.spec.copy(x = 0, y = 0, span = TileSpan(2, 4))) else phone,
            if (tallTile) games.copy(spec = games.spec.copy(x = 2, y = 0)) else games,
            work,
            appTile(2, "Clock", TileSize.MEDIUM), appTile(3, "Music", TileSize.MEDIUM), appTile(4, "Camera", TileSize.WIDE),
        )
        warmIcons(icons, tiles + tiles.flatMap { it.children })
        val open = tiles.firstOrNull { it.spec.id == openFolder }
        val layout = FolderLayout.compute(
            TilePlacement.layout(tiles.map { it.spec }, columns), tiles.indexOfFirst { it === open }.takeIf { it >= 0 },
            open?.children.orEmpty().map { it.spec.size }, columns,
        )
        val backdrop = StartBackdrop.Solid
        CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                    val gap = 4.dp
                    val cell = (maxWidth + gap) / columns
                    @Composable
                    fun Tile(tile: ResolvedTile, p: io.github.danielphingston.metro.ui.start.Placement) {
                        Box(Modifier.offset(cell * p.x, cell * p.y).size(cell * p.width - gap, cell * p.height - gap)) {
                            if (tile.spec.content is TileContent.Folder) {
                                FolderTile(tile, cell, icons, true, open = tile === open)
                            } else {
                                val live = if (tile.label == "Chess") unread else null
                                TileFace(tile.app!!, tile.spec.size, live, false, true, icons, cell)
                            }
                        }
                    }
                    tiles.zip(layout.tiles).forEach { (tile, p) -> Tile(tile, p) }
                    val region = layout.region
                    if (region != null) {
                        FolderRegion(region, layout.tiles[tiles.indexOf(open)], columns, cell, gap, 8.dp, 8.dp)
                        open!!.children.zip(layout.children).forEach { (tile, p) -> Tile(tile, p) }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppListPreview() {
        val context = LocalContext.current
        val icons = remember { IconCache(context) }
        val rows = remember {
            val names = listOf(
                "Alarms", "Calculator", "Calendar", "Camera", "Clock", "Contacts", "Drive", "Files", "Gmail", "Maps",
                "Messages", "Music", "Notes", "Phone", "Photos", "Podcasts", "Settings", "Spotify", "Store", "Weather",
                "WhatsApp",
            )
            val recent = mapOf("Spotify" to 0.5, "WhatsApp" to 2.0, "Notes" to 4.0)
            val apps = names.map { name ->
                val installed = recent[name]?.let { now - (it * 24 * hour).toLong() } ?: (now - 60 * 24 * hour)
                app(name, installTime = installed, uninstallable = name in recent)
            }
            appListRows(apps, "", now)
        }
        Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
            AppListContent(
                rows = rows,
                listState = rememberLazyListState(),
                icons = icons,
                monochrome = true,
                query = "",
                onQueryChange = {},
                onSubmit = {},
                onLetterClick = {},
                onClick = { _, _ -> },
                onLongClick = { _, _ -> },
            )
        }
    }

    @Composable
    private fun SettingsPreview() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Metro.colors.background)
                .padding(start = 20.dp, end = 20.dp, top = 40.dp),
        ) {
            MetroText("METRO SETTINGS", MetroType.appName, color = Metro.colors.subtle)
            MetroText("start+theme", MetroType.pageTitle, maxLines = 1)
            Section("start background")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroChoice("solid", false, {}, Modifier.weight(1f))
                MetroChoice("photo tiles", true, {}, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroChoice("full photo", false, {}, Modifier.weight(1f))
                MetroChoice("wallpaper", false, {}, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            MetroText(
                "Each tile shows the part of your photo behind it, like a window onto one picture.",
                MetroType.caption,
                color = Metro.colors.subtle,
            )
            Spacer(Modifier.height(12.dp))
            MetroButton("change photo", {})
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                MetroText("Tile transparency", MetroType.body, modifier = Modifier.weight(1f))
                MetroText("45%", MetroType.body, color = Metro.colors.accent)
            }
            MetroSlider(0.45f, {})
            MetroToggle("Accent colour from my background", true, {}, description = "Using a colour from your photo")
            MetroToggle("Show more tiles", false, {})
            Section("accent colour")
            AccentGrid(0xFF0050EF.toInt()) {}
        }
    }

    private companion object {
        const val GAMES_ID = 100L
        const val WORK_ID = 101L
    }

    private fun app(label: String, installTime: Long = 0L, uninstallable: Boolean = true) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = uninstallable,
        installTime = installTime,
        loadIcon = { _, _ -> AdaptiveIconDrawable(ColorDrawable(0xFF444444.toInt()), GlyphDrawable(label), GlyphDrawable(label)) },
    )

    /** Stand-in for a themed icon: the app's initial in a ring, drawn in the adaptive safe zone. */
    private class GlyphDrawable(label: String) : Drawable() {
        private val letter = label.take(1)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val r = b.width() / 6f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r / 6f
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = r
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(letter, cx, cy + r / 3f, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
