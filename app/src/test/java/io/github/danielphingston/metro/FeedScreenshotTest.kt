package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.feed.CachedFeed
import io.github.danielphingston.metro.live.feed.Feed
import io.github.danielphingston.metro.live.feed.FeedItem
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.LocalMetroColors
import io.github.danielphingston.metro.ui.theme.LocalTileDecor
import io.github.danielphingston.metro.ui.theme.TileDecor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import io.github.danielphingston.metro.ui.tiles.FeedFace
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The feed tile at each size: a headline over its picture, one without a picture, and the
 * loading, failed, locked and small faces. The picture is a drawn stand-in for a thumbnail.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1220dp-xxhdpi", application = android.app.Application::class)
class FeedScreenshotTest {
    private companion object {
        const val NOW = 1_800_000_000_000L
        const val URL = "https://lemmy.ml/feeds/c/linux.xml?sort=Hot"
    }

    private class Item(
        val x: Int,
        val y: Int,
        val size: TileSize,
        val item: FeedItem?,
        val cached: CachedFeed? = null,
        val unlocked: Boolean = true,
        /** The tile's own colour (else the accent) and whether it shows its background. */
        val colour: Color? = null,
        val background: Boolean = true,
    )

    private val pictured = FeedItem(
        "Canonical is now shipping AI-written security patches to Ubuntu Pro users",
        "https://lemmy.ml/post/55448193",
        "SocialistVibes01",
        NOW - 42 * 60_000L,
        "thumb",
        points = 94,
        comments = 23,
    )
    private val plain = FeedItem("Understanding Linux and choosing your first Linux distro, v2.0", null, "wfh", NOW - 5 * 3_600_000L)

    @Test
    fun faces() {
        val loaded = CachedFeed(URL, Feed("Android", listOf(pictured, plain)), NOW)
        captureRoboImage("src/test/screenshots/feed_tiles.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                        val gap = 4.dp
                        val c = (maxWidth + gap) / 4
                        val m = TileMetrics(c, LocalDensity.current)
                        listOf(
                            Item(0, 0, TileSize.WIDE, pictured, loaded),
                            Item(0, 2, TileSize.MEDIUM, pictured, loaded),
                            Item(2, 2, TileSize.MEDIUM, plain, loaded),
                            Item(0, 4, TileSize.WIDE, plain, loaded),
                            Item(0, 6, TileSize.MEDIUM, null, null),
                            Item(2, 6, TileSize.MEDIUM, null, CachedFeed(URL, null, failedAt = NOW, error = "feed not found")),
                            Item(0, 8, TileSize.MEDIUM, null, null, unlocked = false),
                            Item(2, 8, TileSize.SMALL, null, null),
                            // A headline without a picture on the tile's own colour, and with no background at all.
                            Item(0, 10, TileSize.MEDIUM, plain, loaded, colour = Color(0xFFA20025)),
                            Item(2, 10, TileSize.MEDIUM, plain, loaded, background = false),
                        ).forEach { item ->
                            val colors = Metro.colors.let { base -> item.colour?.let { base.copy(accent = it) } ?: base }
                            CompositionLocalProvider(LocalMetroColors provides colors, LocalTileDecor provides TileDecor(background = item.background)) {
                                Box(Modifier.offset(c * item.x, c * item.y).size(c * item.size.width - gap, c * item.size.height - gap)) {
                                    FeedFace(item.size, "c/linux", item.item, item.cached, item.unlocked, m, NOW) { url, _, _ ->
                                        if (url == null) null else thumbnail().asImageBitmap()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A drawn landscape: sky, sun and hills. */
    private fun thumbnail(): Bitmap {
        val w = 320
        val h = 200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFF4F86C6.toInt())
        p.color = 0xFFF4D35E.toInt(); canvas.drawCircle(w * 0.75f, h * 0.3f, h * 0.14f, p)
        p.color = 0xFF3B7A57.toInt(); canvas.drawCircle(w * 0.25f, h * 1.15f, h * 0.65f, p)
        p.color = 0xFF2E5E43.toInt(); canvas.drawCircle(w * 0.8f, h * 1.3f, h * 0.7f, p)
        return bitmap
    }
}
