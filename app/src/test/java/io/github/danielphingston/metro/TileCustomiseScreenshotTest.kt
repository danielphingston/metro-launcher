package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LaunchTarget
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.PhotoUse
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileOptions
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.TileStyle
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.StartState
import io.github.danielphingston.metro.ui.start.TileCustomiseSheet
import io.github.danielphingston.metro.ui.start.TileHost
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/**
 * The customise sheet for each kind of tile, and a start screen of tiles customised in the new
 * ways. The screen is tall enough to show a whole sheet (it scrolls on a phone).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1300dp-xhdpi", application = android.app.Application::class)
class TileCustomiseScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val appKey = AppKey("com.example.phone", "Main", 0)
    private val photo = "content://test/customise_photo"

    private fun sheet(name: String, spec: TileSpec, widgetSettings: Boolean = false, startTransparency: Int = 0) {
        TilePhotoStub.install(photo)
        val options = TileOptions.available(spec, widgetSettings = widgetSettings)
        rule.setContent {
            MetroTheme(LauncherSettings()) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    TileCustomiseSheet(
                        spec = spec, options = options, startTransparency = startTransparency,
                        onShowName = {}, onShowIcon = {}, onIconSource = {}, onPhoto = {}, onBackground = {},
                        onColour = {}, onTransparency = {}, onWidgetSettings = if (widgetSettings) ({}) else null, onDismiss = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/customise_$name.png", roborazziOptions = ScreenshotOptions)
    }

    private fun spec(content: TileContent) = TileSpec(1, TileSize.MEDIUM, content, 0, 0)

    /** "Show icon" can't go: it is the last thing the tile shows, so it is faded with a hint. */
    @Test
    fun app() = sheet("app", spec(TileContent.App(appKey)).copy(style = TileStyle.ICON_ONLY))

    @Test
    fun shortcut() = sheet("shortcut", spec(TileContent.Shortcut("com.example", "s", 0, "chat")).copy(icon = "glyph:gear", color = "accent"))

    @Test
    fun action() = sheet(
        "action",
        TileOptions.withNameShown(
            TileOptions.withPhoto(spec(TileContent.Action("cats", ActionIcon.Glyph("gear"), LaunchTarget.Web("https://example.com"))), PhotoUse.ICON, photo),
            false, hasLook = true,
        ),
    )

    @Test
    fun folder() = sheet(
        "folder",
        spec(TileContent.Folder("games", listOf(TileSpec(2, TileSize.SMALL, TileContent.App(appKey)))))
            .copy(style = TileStyle.IMAGE_ICON, image = photo, transparency = 40),
        startTransparency = 45,
    )

    @Test
    fun widget() = sheet("widget", spec(TileContent.Widget(5, "com.example/.Clock", 0, transparent = true)))

    @Test
    fun widgetWithSettings() = sheet("widget_settings", spec(TileContent.Widget(5, "com.example/.Clock", 0)), widgetSettings = true)

    @Test
    fun weather() = sheet("weather", spec(TileContent.Weather).copy(hideName = true))

    @Test
    fun photos() = sheet("photos", spec(TileContent.Photos))

    @Test
    fun people() = sheet("people", spec(TileContent.People).copy(background = false))

    @Test
    fun quickSetting() = sheet("quick_setting", spec(TileContent.QuickSetting(QuickSettingKind.FLASHLIGHT)).copy(color = "#E3C800"))

    @Test
    fun assistant() = sheet("assistant", spec(TileContent.Assistant))

    @Test
    fun me() = sheet("me", spec(TileContent.Me).copy(transparency = 0))

    @Test
    fun layoutSwitch() = sheet("layout_switch", spec(TileContent.LayoutSwitch(null)))

    // ------------------------------------------------------------------ start screen

    /**
     * Tiles customised in the new ways over the photo-tiles background at 45% transparency, row by
     * row: name hidden (and a clock whose small label is hidden), icon hidden, a photo filling the
     * tile with its icon / without / with neither icon nor name; no background (an app and a
     * folder); a fixed colour on an opaque tile, an opaque and a fully clear tile beside a default one.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun startCustomised() {
        // Medium tiles here are 387 px across.
        TilePhotoStub.install(photo, 370..400)
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 24, 10, 42) }.timeInMillis
        val clockLive = LiveContent.Clock(ClockState(now, null), false)
        fun app(id: Long, label: String, change: (TileSpec) -> TileSpec = { it }): ResolvedTile {
            val info = appInfo(label)
            return ResolvedTile(change(TileSpec(id, TileSize.MEDIUM, TileContent.App(info.key))), info, label)
        }
        val tiles = listOf(
            app(1, "Phone") { TileOptions.withNameShown(it, false, hasLook = true) },
            app(2, "Clock") { TileOptions.withNameShown(it, false, hasLook = false) },
            app(3, "Mail") { TileOptions.withIconShown(it, false) },
            app(4, "Music") { TileOptions.withPhoto(it, PhotoUse.FILL, photo) },
            app(5, "Photos") { TileOptions.withIconShown(TileOptions.withPhoto(it, PhotoUse.FILL, photo), false) },
            app(6, "Gallery") { TileOptions.withNameShown(TileOptions.withIconShown(TileOptions.withPhoto(it, PhotoUse.FILL, photo), false), false, hasLook = true) },
            app(7, "Notes") { TileOptions.withBackground(it, false) },
            ResolvedTile(
                TileOptions.withBackground(TileSpec(8, TileSize.MEDIUM, TileContent.Folder("games", listOf(10L, 11L, 12L).map { TileSpec(it, TileSize.SMALL, TileContent.App(appInfo("Chess").key)) }), 0, 0), false),
                null, "games", listOf("Chess", "Cards", "Xbox").mapIndexed { i, l -> app(10L + i, l) },
            ),
            app(9, "Store") { TileOptions.withTransparency(it.copy(color = "#E3C800"), 0) },
            app(13, "Maps") { TileOptions.withTransparency(it, 0) },
            app(14, "Camera"),
            app(15, "Video") { TileOptions.withTransparency(it, 100) },
        ).map { it.copy(spec = it.spec.copy(x = TileSpec.UNPLACED, y = TileSpec.UNPLACED)) }
        val image = backgroundPhoto()
        rule.setContent {
            MetroTheme(LauncherSettings(columns = 6)) {
                val context = LocalContext.current
                val icons = remember { IconCache(context) }
                val density = LocalDensity.current
                val columns = 6
                val placements = TilePlacement.layout(tiles.map { it.spec }, columns)
                val state = remember { StartState(ScrollState(0)) }
                val backdrop = remember {
                    StartBackdrop.of(LauncherSettings(background = StartBackground.PHOTO_TILES, tileTransparency = 45), image) { 0f }
                }
                CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                            val gap = 4.dp
                            val cell = (maxWidth + gap) / columns
                            val cellPx = with(density) { cell.toPx() }
                            tiles.zip(placements).forEach { (tile, p) ->
                                TileHost(
                                    tile = tile, placement = p, cellPx = cellPx, gapPx = with(density) { gap.toPx() },
                                    cell = cell, columns = columns, state = state,
                                    live = if (tile.label == "Clock") clockLive else null,
                                    flipped = false, monochromeIcons = true, icons = icons,
                                    onUnpin = {}, onResize = {}, onSetSpan = { _, _ -> }, onLaunch = {}, onLongPress = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        repeat(8) {
            Thread.sleep(250)
            rule.waitForIdle()
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/start_customised.png", roborazziOptions = ScreenshotOptions)
    }

    /** A sunset behind hills, as in [StartScreenshotTest]. */
    private fun backgroundPhoto(): ImageBitmap {
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
        return bitmap.asImageBitmap()
    }

    private fun appInfo(label: String) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
        installTime = 0L,
        loadIcon = { _, _ ->
            AdaptiveIconDrawable(
                ColorDrawable(0xFF444444.toInt()),
                OldTileStylesScreenshotTest.GlyphDrawable(label),
                OldTileStylesScreenshotTest.GlyphDrawable(label),
            )
        },
    )
}
