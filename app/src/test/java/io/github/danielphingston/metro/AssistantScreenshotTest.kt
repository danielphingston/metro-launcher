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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.AssistantModel
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LastAsked
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SavedReply
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.search.AskAssistantProvider
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.SearchContext
import kotlinx.coroutines.runBlocking
import io.github.danielphingston.metro.ui.search.SearchContent
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import io.github.danielphingston.metro.ui.tiles.AssistantTile
import io.github.danielphingston.metro.ui.tiles.MetroPlusPanel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Renders the assistant tile, the search row and the paid-tiles picker on the JVM. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class AssistantScreenshotTest {
    private val claude = app("Claude", "com.anthropic.claude")
    private val chatgpt = app("ChatGPT", "com.openai.chatgpt")
    private companion object {
        const val NOW = 1_800_000_000_000L
    }

    private val asked = LastAsked("how do I add pull-down search to a launcher?", 1L)

    private fun claudeNotes() = AppNotifications(
        3,
        listOf(
            NotificationPreview(
                "Pull down search refinements",
                "Sure. Track the overscroll distance and open search once it passes a threshold. " +
                    "Give a haptic tick at the threshold so the release feels deliberate, and ignore sideways pulls.",
                1L,
            ),
        ),
    )

    /** An app whose notification title is only its own name: the title is left out. */
    private fun nameOnlyNotes() = AppNotifications(
        1,
        listOf(NotificationPreview("ChatGPT", "Here are three ways to phrase that email, from casual to formal.", 1L)),
    )

    private class Item(val x: Int, val y: Int, val size: TileSize, val app: AppInfo, val model: AssistantModel?, val flipped: Boolean = false)

    private fun model(app: AppInfo, notes: AppNotifications?, asked: LastAsked? = this.asked) =
        AssistantModel.of(app.label, notes, true, asked)

    private fun tiles() = listOf(
        Item(0, 0, TileSize.SMALL, claude, model(claude, claudeNotes())),
        Item(1, 0, TileSize.SMALL, claude, model(claude, AppNotifications(0, emptyList()))),
        Item(0, 1, TileSize.SMALL, chatgpt, model(chatgpt, nameOnlyNotes())),
        Item(1, 1, TileSize.SMALL, claude, model(claude, null)),
        Item(2, 0, TileSize.MEDIUM, claude, model(claude, claudeNotes())),
        Item(0, 2, TileSize.WIDE, claude, model(claude, claudeNotes())),
        Item(0, 4, TileSize.LARGE, claude, model(claude, claudeNotes())),
    )

    @Test
    fun tilesDark() = captureTiles("assistant_tiles_dark", StartBackground.SOLID, tiles())

    @Test
    fun tilesPhoto() = captureTiles("assistant_tiles_photo", StartBackground.PHOTO_TILES, tiles())

    @Test
    fun backFaces() = captureTiles(
        "assistant_back", StartBackground.SOLID,
        listOf(
            Item(0, 0, TileSize.MEDIUM, claude, model(claude, claudeNotes()), flipped = true),
            Item(2, 0, TileSize.MEDIUM, claude, model(claude, claudeNotes()), flipped = true),
            Item(0, 2, TileSize.WIDE, claude, model(claude, claudeNotes()), flipped = true),
            Item(0, 4, TileSize.LARGE, claude, model(claude, claudeNotes()), flipped = true),
        ),
    )

    @Test
    fun emptyStates() = captureTiles(
        "assistant_empty", StartBackground.SOLID,
        listOf(
            Item(0, 0, TileSize.MEDIUM, claude, model(claude, null, asked = null)),
            Item(2, 0, TileSize.MEDIUM, claude, model(claude, null)),
            Item(0, 2, TileSize.WIDE, claude, model(claude, null, asked = null)),
            Item(0, 4, TileSize.WIDE, claude, model(claude, null)),
            
        ),
    )

    /** An assistant whose notifications carry only its own name as the title. */
    @Test
    fun fallbackLayout() = captureTiles(
        "assistant_fallback", StartBackground.SOLID,
        listOf(
            Item(0, 0, TileSize.MEDIUM, chatgpt, model(chatgpt, nameOnlyNotes())),
            Item(2, 0, TileSize.MEDIUM, chatgpt, model(chatgpt, nameOnlyNotes()), flipped = true),
            Item(0, 2, TileSize.WIDE, chatgpt, model(chatgpt, nameOnlyNotes())),
            Item(0, 4, TileSize.LARGE, chatgpt, model(chatgpt, nameOnlyNotes())),
        ),
    )

    /** The saved last reply once the notification is gone: quieter text, no count, relative time. */
    @Test
    fun readState() {
        val saved = SavedReply(
            claude.key.packageName, "Pull down search refinements",
            "Sure. Track the overscroll distance and open search once it passes a threshold. " +
                "Give a haptic tick at the threshold so the release feels deliberate, and ignore sideways pulls.",
            NOW - 2 * 3_600_000L,
        )
        fun read(asked: LastAsked? = this.asked) = AssistantModel.of(claude.label, null, true, asked, saved, claude.key.packageName)
        captureTiles(
            "assistant_read", StartBackground.SOLID,
            listOf(
                Item(0, 0, TileSize.MEDIUM, claude, read()),
                Item(2, 0, TileSize.MEDIUM, claude, read(asked = null)),
                Item(0, 2, TileSize.WIDE, claude, read()),
                Item(0, 4, TileSize.LARGE, claude, read()),
            ),
        )
    }

    @Test
    fun searchRow() {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val apps = listOf(claude, app("Chrome", "com.example.chrome"), app("Calendar", "com.example.calendar"))
        apps.forEach { icons.load(it, 28 * 3, true); icons.load(it, 44 * 3, false) }
        val query = "why is the sky blue"
        captureRoboImage("src/test/screenshots/assistant_search.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                val rows = remember {
                    val ask = runBlocking {
                        AskAssistantProvider.results(query, SearchContext(RuntimeEnvironment.getApplication(), apps, LauncherSettings(assistant = claude.key.packageName)))
                    }
                    searchRows(apps, query, emptyList(), SearchEngine.GOOGLE, 0L, listOf(ProvidedItems(AskAssistantProvider, ask)))
                }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    SearchContent(
                        query = query, onQueryChange = {}, onSubmit = {}, rows = rows, icons = icons,
                        monochrome = true, plainIcons = false, focusRequester = focus,
                        onActivate = { _, _ -> }, onLongClick = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun pickerWithSections() {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        icons.load(claude, (40 * 72 / 96f * 3).roundToInt(), true)
        captureRoboImage("src/test/screenshots/assistant_picker.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings()) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    MetroPlusPanel(
                        unlocked = true, price = null, assistantApp = claude, icons = icons, monochrome = true,
                        onBuy = {}, onAdd = { _, _ -> }, modifier = Modifier.align(Alignment.BottomCenter),
                        // The end of the quick settings section and the assistant section below it.
                        listState = rememberLazyListState(initialFirstVisibleItemIndex = 11),
                    )
                }
            }
        }
    }

    private fun captureTiles(name: String, background: StartBackground, items: List<Item>) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val cell = (411 - 16 + 4) / 4f
        // Icons load on a background thread the capture doesn't wait for: render them up front.
        for (item in items) for (base in listOf(40f, 36f, 32f, 28f)) {
            icons.load(item.app, (base * cell / 96f * 3f).roundToInt(), true)
        }
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                val backdrop = remember {
                    val settings = LauncherSettings(background = background)
                    StartBackdrop.of(settings, if (settings.showsPhoto) photo() else null) { 0f }
                }
                CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                            val gap = 4.dp
                            val c = (maxWidth + gap) / 4
                            items.forEachIndexed { i, item ->
                                val spec = TileSpec(i + 1L, item.size, TileContent.Assistant)
                                val tile = ResolvedTile(spec, item.app, item.app.label)
                                Box(Modifier.offset(c * item.x, c * item.y).size(c * item.size.width - gap, c * item.size.height - gap)) {
                                    AssistantTile(tile, item.model?.let { LiveContent.Assistant(it) }, item.flipped, true, icons, c, NOW)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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
        return bitmap.asImageBitmap()
    }

    private fun app(label: String, pkg: String) = AppInfo(
        key = AppKey(pkg, "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = android.content.ComponentName(pkg, "Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> AdaptiveIconDrawable(ColorDrawable(0xFF444444.toInt()), Initial(label), Initial(label)) },
    )

    private class Initial(label: String) : Drawable() {
        private val letter = label.take(1)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.textSize = b.height() * 0.4f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() + paint.textSize * 0.35f, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required override", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
