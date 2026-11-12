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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileOptions
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
import org.junit.Assert.assertEquals
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
    fun oldStylesDrawAsTheyAlwaysHave() = drawAndCapture(oldLayout(photoUri()), "start_old_styles")

    /**
     * Search (action) tiles in every old style, size and picture kind: a glyph, a contact (no
     * contacts permission here, so the person drawing) and a picked pack-style glyph icon, with and
     * without their photo. `start_old_action_styles.png` was recorded with the code from before the
     * customise sheet.
     */
    @Test
    @Config(qualifiers = "w411dp-h1700dp-xhdpi")
    fun oldActionStylesDrawAsTheyAlwaysHave() = drawAndCapture(
        oldActionLayout(photoUri()), "start_old_action_styles",
        // A wrongly shown or hidden label is a few hundred pixels of this tall image: compare closely.
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0001f)),
    )

    private fun oldActionLayout(photo: String): String {
        var id = 0
        fun action(size: String, style: String?, label: String, icon: String, image: Boolean = false, extra: String = ""): String {
            id++
            return """{"id":$id,"size":"$size","type":"action","label":"$label",$icon,"target":{"kind":"web","url":"https://example.com"}""" +
                (style?.let { ""","style":"$it"""" } ?: "") + (if (image) ""","image":"$photo"""" else "") + extra + "}"
        }
        val glyph = """"glyph":"gear""""
        val contact = """"contact":"content://com.android.contacts/contacts/lookup/x/1""""
        val styles = listOf(null, "iconOnly", "name", "customIcon", "image", "imageIcon")
        val tiles = ArrayList<String>()
        for (style in styles) {
            val photo = style == "customIcon" || style == "image" || style == "imageIcon"
            tiles += action("MEDIUM", style, "g ${style ?: "icon"}", glyph, image = photo)
            tiles += action("MEDIUM", style, "c ${style ?: "icon"}", contact, image = photo)
            tiles += action("SMALL", style, "s ${style ?: "icon"}", glyph, image = photo)
        }
        // Photo styles whose photo is gone, and a picked icon in each plain style.
        for (style in listOf("customIcon", "image", "imageIcon")) {
            tiles += action("MEDIUM", style, "gone $style", glyph)
            tiles += action("MEDIUM", style, "cgone $style", contact)
        }
        for (style in listOf(null, "iconOnly", "name")) {
            tiles += action("MEDIUM", style, "picked ${style ?: "icon"}", glyph, extra = ""","icon":"glyph:alarm"""")
        }
        tiles += action("WIDE", "iconOnly", "wide", glyph)
        tiles += action("LARGE", null, "large", contact)
        return tiles.joinToString(",", "[", "]")
    }

    private val contactUri = "content://com.android.contacts/contacts/lookup/p/2"

    /** Contact tiles in the old styles whose contact has a photo. `start_old_contact_photos.png` was recorded with the code from before the customise sheet. */
    @Test
    fun oldContactTilesWithAPhotoDrawAsTheyAlwaysHave() {
        ContactPhotoStub.install(contactUri)
        drawAndCapture(
            oldContactLayout(), "start_old_contact_photos",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0001f)),
        )
    }

    private fun oldContactLayout(): String {
        var id = 0
        fun contact(size: String, style: String?, label: String, extra: String = ""): String {
            id++
            return """{"id":$id,"size":"$size","type":"action","label":"$label","contact":"$contactUri","target":{"kind":"web","url":"https://example.com"}""" +
                (style?.let { ""","style":"$it"""" } ?: "") + extra + "}"
        }
        return listOf(
            contact("MEDIUM", null, "p icon"),
            contact("MEDIUM", "iconOnly", "p iconOnly"),
            contact("MEDIUM", "name", "p name"),
            contact("MEDIUM", null, "p picked", extra = ""","icon":"glyph:alarm""""),
            contact("SMALL", null, "p small"),
            contact("SMALL", "iconOnly", "p small iconOnly"),
            contact("WIDE", null, "p wide"),
            contact("WIDE", "iconOnly", "p wide iconOnly"),
        ).joinToString(",", "[", "]")
    }

    /**
     * "Show name" off on a contact's photo face keeps the photo and drops only the name strip; the
     * old "icon only" contact next to it stays the person drawing without a name.
     */
    @Test
    fun contactPhotoWithNameHiddenKeepsThePhoto() {
        ContactPhotoStub.install(contactUri)
        val old = TileJson.decode(JSONArray(oldContactLayout()))
        val photo = old.first { (it.content as TileContent.Action).label == "p icon" }
        val wide = old.first { (it.content as TileContent.Action).label == "p wide" }
        val iconOnly = old.first { (it.content as TileContent.Action).label == "p iconOnly" }
        val tiles = listOf(photo, wide).map { TileOptions.withNameShown(it, shown = false, hasLook = true) } + iconOnly
        // What is stored survives a save and a restart.
        val stored = TileJson.decode(TileJson.encode(tiles))
        assertEquals(tiles, stored)
        drawAndCapture(TileJson.encode(stored).toString(), "start_contact_photo_no_name")
        rule.onAllNodesWithText("p icon").assertCountEquals(0)
        rule.onAllNodesWithText("p wide").assertCountEquals(0)
        rule.onAllNodesWithText("p iconOnly").assertCountEquals(0)
    }

    /** "Show name" off on a search tile hides its label, for a glyph and for a contact. */
    @Test
    fun actionTileWithNameHiddenHasNoLabel() {
        val glyph = TileJson.decode(JSONArray(oldActionLayout(photoUri()))).first { (it.content as TileContent.Action).label == "g icon" }
        val contact = TileJson.decode(JSONArray(oldActionLayout(photoUri()))).first { (it.content as TileContent.Action).label == "c icon" }
        val hidden = listOf(glyph, contact.copy(id = 99)).map { TileOptions.withNameShown(it, shown = false, hasLook = true) }
        draw(hidden)
        rule.waitForIdle()
        rule.onAllNodesWithText("g icon").assertCountEquals(0)
        rule.onAllNodesWithText("c icon").assertCountEquals(0)
    }

    private fun drawAndCapture(layout: String, golden: String, options: RoborazziOptions = ScreenshotOptions) {
        draw(TileJson.decode(JSONArray(layout)))
        // Icons and photos load on background threads; give them time to arrive and settle.
        repeat(8) {
            Thread.sleep(250)
            rule.waitForIdle()
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/$golden.png", roborazziOptions = options)
    }

    private fun draw(tiles: List<TileSpec>) {
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

/**
 * Contact photos for screenshot tests: Robolectric has no contacts provider, so the contact
 * permission is granted and the photo put straight into PeopleSource's cache, marked as looked up
 * just now, under every size a small, medium or wide tile asks for.
 */
internal object ContactPhotoStub {
    fun install(uri: String) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        val side = 96
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, side.toFloat(), 0xFF7FB77E.toInt(), 0xFF2F5D50.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFF2D0B5.toInt()
        canvas.drawCircle(side * 0.5f, side * 0.4f, side * 0.2f, paint)
        canvas.drawCircle(side * 0.5f, side * 1.05f, side * 0.38f, paint)
        val owner = Class.forName("io.github.danielphingston.metro.live.PeopleSource")
        @Suppress("UNCHECKED_CAST")
        val cache = owner.getDeclaredField("cache").apply { isAccessible = true }.get(null) as android.util.LruCache<String, Bitmap>
        @Suppress("UNCHECKED_CAST")
        val checked = owner.getDeclaredField("contactChecked").apply { isAccessible = true }.get(null) as android.util.LruCache<String, Any>
        val check = Class.forName("io.github.danielphingston.metro.live.PeopleSource\$ContactCheck")
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        for (px in (128..138) + (261..271) + (527..537)) {
            val key = "contact:$uri#$px"
            cache.put(key, bitmap)
            checked.put(key, check.newInstance(android.os.SystemClock.elapsedRealtime(), true))
        }
    }
}
