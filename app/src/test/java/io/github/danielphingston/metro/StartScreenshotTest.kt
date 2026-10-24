package io.github.danielphingston.metro

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarEvent
import io.github.danielphingston.metro.live.ClockState
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.start.TileFace
import io.github.danielphingston.metro.ui.start.TileGrid
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/**
 * Renders a representative start screen on the JVM so tile layout and faces can be reviewed
 * without a device. Output: app/build/outputs/roborazzi/.
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

    private fun capture(name: String, theme: ThemeMode, columns: Int, accent: Int = LauncherSettings.DEFAULT_ACCENT) {
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            MetroTheme(LauncherSettings(theme = theme, accent = accent, columns = columns)) {
                StartPreview(samples(), columns)
            }
        }
    }

    @Composable
    private fun StartPreview(samples: List<Sample>, columns: Int) {
        val context = LocalContext.current
        val icons = remember { IconCache(context) }
        Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 52.dp)) {
                val gap = 8.dp
                val cell = (maxWidth + gap) / columns
                val placements = TileGrid.pack(samples.map { it.size }, columns)
                samples.zip(placements).forEach { (sample, p) ->
                    Box(
                        Modifier
                            .offset(cell * p.x, cell * p.y)
                            .size(cell * p.width - gap, cell * p.height - gap),
                    ) {
                        TileFace(sample.app, sample.size, sample.live, sample.flipped, true, icons, cell)
                    }
                }
            }
        }
    }

    private fun app(label: String) = AppInfo(
        key = AppKey("com.example.${label.lowercase()}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName("com.example", "Main"),
        isUninstallable = true,
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
