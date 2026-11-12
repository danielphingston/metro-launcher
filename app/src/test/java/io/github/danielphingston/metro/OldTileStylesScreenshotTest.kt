package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.StartState
import io.github.danielphingston.metro.ui.start.TileHost
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every tile style a layout saved before the customise sheet can hold, read from that old JSON
 * and drawn by the real tile code (app, folder and search tiles, with and without their photo).
 *
 * The golden `start_old_styles.png` was recorded with the code from before the customise sheet;
 * this test only uses what existed then, so it proves the new code draws old layouts the same.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1000dp-xhdpi", application = android.app.Application::class)
class OldTileStylesScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private fun photoUri(): String = TilePhotoStub.install("content://test/old_style_photo")

    /** A layout as an old build saved it: only "style", "image", "icon" and "color". */
    private fun oldLayout(photo: String): String {
        fun app(id: Int, label: String, size: String, style: String?, image: Boolean = false, extra: String = "") =
            """{"id":$id,"size":"$size","type":"app","app":"com.example.${label.lowercase()}/Main#0"""" +
                (style?.let { ""","style":"$it"""" } ?: "") + (if (image) ""","image":"$photo"""" else "") + extra + "}"
        val kids = """[{"id":101,"size":"SMALL","type":"app","app":"com.example.chess/Main#0"},""" +
            """{"id":102,"size":"SMALL","type":"app","app":"com.example.cards/Main#0"},""" +
            """{"id":103,"size":"SMALL","type":"app","app":"com.example.xbox/Main#0"}]"""
        fun folder(id: Int, name: String, style: String?, image: Boolean = false) =
            """{"id":$id,"size":"MEDIUM","type":"folder","name":"$name","children":$kids""" +
                (style?.let { ""","style":"$it"""" } ?: "") + (if (image) ""","image":"$photo"""" else "") + "}"
        fun action(id: Int, size: String, style: String?) =
            """{"id":$id,"size":"$size","type":"action","label":"cats","glyph":"gear","target":{"kind":"web","url":"https://example.com"}""" +
                (style?.let { ""","style":"$it"""" } ?: "") + "}"
        return listOf(
            app(1, "Phone", "MEDIUM", null),
            app(2, "Mail", "MEDIUM", "iconOnly"),
            app(3, "Notes", "MEDIUM", "name"),
            app(4, "Camera", "MEDIUM", "customIcon", image = true),
            app(5, "Photos", "MEDIUM", "image", image = true),
            app(6, "Music", "MEDIUM", "imageIcon", image = true),
            app(7, "Maps", "SMALL", "name"),
            app(8, "Store", "SMALL", "iconOnly"),
            app(9, "Video", "SMALL", "image", image = true),
            app(10, "Games", "SMALL", "customIcon", image = true),
            folder(11, "games", null),
            folder(12, "work", "iconOnly"),
            folder(13, "fun", "imageIcon", image = true),
            folder(14, "trips", "image", image = true),
            // A photo style whose photo is gone draws as the plain tile.
            app(15, "Files", "MEDIUM", "image"),
            app(16, "Clock", "MEDIUM", null, extra = ""","color":"#E3C800""""),
            action(17, "WIDE", null),
            action(18, "MEDIUM", "name"),
            folder(19, "empty photo", "image"),
        ).joinToString(",", "[", "]")
    }

    @Test
    fun oldStylesDrawAsTheyAlwaysHave() {
        val tiles = TileJson.decode(JSONArray(oldLayout(photoUri())))
        rule.setContent {
            MetroTheme(LauncherSettings(columns = 6)) {
                val context = LocalContext.current
                val icons = remember { IconCache(context) }
                val density = LocalDensity.current
                val columns = 6
                val placements = TilePlacement.layout(tiles, columns)
                val state = remember { StartState(ScrollState(0)) }
                val backdrop = StartBackdrop.Solid
                CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 24.dp)) {
                            val gap = 4.dp
                            val cell = (maxWidth + gap) / columns
                            val cellPx = with(density) { cell.toPx() }
                            tiles.zip(placements).forEach { (spec, p) ->
                                TileHost(
                                    tile = resolve(spec),
                                    placement = p,
                                    cellPx = cellPx,
                                    gapPx = with(density) { gap.toPx() },
                                    cell = cell,
                                    columns = columns,
                                    state = state,
                                    live = null,
                                    flipped = false,
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
                    }
                }
            }
        }
        // Icons and photos load on background threads; give them time to arrive and settle.
        repeat(8) {
            Thread.sleep(250)
            rule.waitForIdle()
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/start_old_styles.png", roborazziOptions = ScreenshotOptions)
    }

    private fun resolve(spec: TileSpec): ResolvedTile = when (val c = spec.content) {
        is TileContent.App -> app(c.app).let { ResolvedTile(spec, it, it.label) }
        is TileContent.Folder -> ResolvedTile(spec, null, c.name, c.children.map(::resolve))
        is TileContent.Action -> ResolvedTile(spec, null, c.label)
        else -> ResolvedTile(spec, null, "tile")
    }

    private fun app(key: AppKey): AppInfo {
        val label = key.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return AppInfo(
            key = key,
            label = label,
            user = Process.myUserHandle(),
            component = android.content.ComponentName(key.packageName, "Main"),
            isUninstallable = true,
            installTime = 0L,
            loadIcon = { _, _ -> AdaptiveIconDrawable(ColorDrawable(0xFF444444.toInt()), GlyphDrawable(label), GlyphDrawable(label)) },
        )
    }

    /** Stand-in for a themed icon: the app's initial in a ring, drawn in the adaptive safe zone. */
    internal class GlyphDrawable(label: String) : Drawable() {
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

/**
 * Tile photos for screenshot tests. Robolectric can't run ImageDecoder, so the photo a tile asks
 * for is put straight into the tile photo cache (TileImages), under every square size a tile or
 * an icon can ask for; the photo is a sunset-coloured gradient with a sun.
 */
internal object TilePhotoStub {
    fun install(uri: String, sizes: IntRange = 20..300): String {
        val side = 96
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), 0xFFE8644A.toInt(), 0xFF0B1D51.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFE9B0.toInt()
        canvas.drawCircle(side * 0.65f, side * 0.4f, side * 0.18f, paint)
        val image = bitmap.asImageBitmap()
        val owner = Class.forName("io.github.danielphingston.metro.ui.start.TileImages")
        val field = owner.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>
        for (px in sizes) cache.put("$uri@${px}x$px", image)
        return uri
    }
}
