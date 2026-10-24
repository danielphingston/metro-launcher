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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarEvent
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.live.NotificationPreview
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
import io.github.danielphingston.metro.ui.start.EditBadges
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileFace
import io.github.danielphingston.metro.ui.start.TileGrid
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

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

    @Test
    fun startEditing() = capture("start_editing", ThemeMode.DARK, 4, editing = true)

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
    fun appList() = captureRoboImage("build/outputs/roborazzi/app_list.png") {
        MetroTheme(LauncherSettings()) { AppListPreview() }
    }

    @Test
    fun appListLight() = captureRoboImage("build/outputs/roborazzi/app_list_light.png") {
        MetroTheme(LauncherSettings(theme = ThemeMode.LIGHT, accent = 0xFF6A00FF.toInt())) { AppListPreview() }
    }

    @Test
    fun menuFlyout() = captureRoboImage("build/outputs/roborazzi/menu_flyout.png") {
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
    fun settingsControls() = captureRoboImage("build/outputs/roborazzi/settings_controls.png") {
        MetroTheme(LauncherSettings(accent = 0xFF0050EF.toInt())) { SettingsPreview() }
    }

    @Test
    fun settingsControlsLight() = captureRoboImage("build/outputs/roborazzi/settings_controls_light.png") {
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
    ) {
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            MetroTheme(LauncherSettings(theme = theme, accent = accent, columns = columns)) {
                val backdrop = remember {
                    val settings = LauncherSettings(background = background, tileTransparency = transparency)
                    StartBackdrop.of(settings, if (settings.showsPhoto) photo() else null) { 0f }
                }
                StartPreview(samples(), columns, backdrop, editing)
            }
        }
    }

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
    private fun StartPreview(samples: List<Sample>, columns: Int, backdrop: StartBackdrop, editing: Boolean = false) {
        val context = LocalContext.current
        val icons = remember { IconCache(context) }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                    val gap = 4.dp
                    val cell = (maxWidth + gap) / columns
                    val placements = TileGrid.pack(samples.map { it.size }, columns)
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
                                },
                        ) {
                            TileFace(sample.app, sample.size, sample.live, sample.flipped, true, icons, cell)
                        }
                    }
                    if (editing) {
                        EditBadges(placements[1], with(density) { cell.toPx() }, with(density) { gap.toPx() }, {}, {})
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
