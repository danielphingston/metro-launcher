package io.github.danielphingston.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.tracking.ParcelEntry
import io.github.danielphingston.metro.live.tracking.ParcelStage
import io.github.danielphingston.metro.live.tracking.TrackImages
import io.github.danielphingston.metro.live.tracking.TrackItem
import io.github.danielphingston.metro.live.tracking.TrackKind
import io.github.danielphingston.metro.live.tracking.TrackProgress
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.start.LiveContent
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.TrackingModel
import io.github.danielphingston.metro.ui.tiles.TrackingReveal
import io.github.danielphingston.metro.ui.tiles.TrackingTile
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The tracking tile's four situations: a ride with its OTP (hidden, then tapped open), food on its
 * way with Android 16 progress, parcels arriving today, and nothing on the way. Pictures are drawn
 * stand-ins for what the apps put in their notifications.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TrackingScreenshotTest {
    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MIN = 60_000L
    }

    private val uber = app("Uber", "com.ubercab")
    private val swiggy = app("Swiggy", "in.swiggy.android")
    private val rapido = app("Rapido", "com.rapido.passenger")
    private val amazon = app("Amazon", "in.amazon.mShop.android.shopping")
    private val flipkart = app("Flipkart", "com.flipkart.android")
    private val apps = listOf(uber, swiggy, rapido, amazon, flipkart).associateBy { it.key.notificationKey }

    @After
    fun hideCodes() = TrackingReveal.hide()

    private class Item(val x: Int, val y: Int, val size: TileSize, val model: TrackingModel, val images: Map<String, TrackImages> = emptyMap())

    private fun ride(key: String) = TrackItem(
        key, uber.key.packageName, uber.key.notificationKey, TrackKind.RIDE, "Driver arriving", "Ramesh · White Swift Dzire",
        NOW - MIN, etaAt = NOW + 4 * MIN, progress = TrackProgress.of(60, 100, false, listOf(33, 33, 34)),
        otp = "4821", plate = "KA 01 AB 1234",
    )

    private fun model(live: List<TrackItem> = emptyList(), parcels: List<ParcelEntry> = emptyList()) =
        TrackingModel.of(live, parcels, accessGranted = true, showContent = true, now = NOW)

    @Test
    fun rideWithOtp() {
        val hidden = ride("ride-hidden")
        val shown = ride("ride-shown")
        // The second wide tile is the one whose code was tapped open.
        TrackingReveal.toggle(shown.key)
        val pictures = mapOf(hidden.key to TrackImages(driver(), car()), shown.key to TrackImages(driver(), car()))
        captureTiles(
            "tracking_ride_otp",
            listOf(
                Item(0, 0, TileSize.WIDE, model(listOf(hidden)), pictures),
                Item(0, 2, TileSize.WIDE, model(listOf(shown)), pictures),
                Item(0, 4, TileSize.MEDIUM, model(listOf(hidden)), pictures),
                Item(2, 4, TileSize.SMALL, model(listOf(hidden)), pictures),
            ),
        )
    }

    @Test
    fun foodInProgress() {
        val food = TrackItem(
            "food", swiggy.key.packageName, swiggy.key.notificationKey, TrackKind.FOOD, "Out for delivery",
            "Meghana Foods · 2 items\nSuresh is on the way", NOW - MIN, etaAt = NOW + 12 * MIN,
            progress = TrackProgress.of(255, 400, false, listOf(100, 100, 100, 100), listOf(0, 100, 200, 400)),
        )
        val cab = TrackItem(
            "cab", rapido.key.packageName, rapido.key.notificationKey, TrackKind.RIDE, "Captain arriving", "",
            NOW - 2 * MIN, etaAt = NOW + 18 * MIN, otp = "3390", plate = "KA 05 HX 7781",
        )
        val pictures = mapOf(food.key to TrackImages(dish(), scooter()), cab.key to TrackImages(driver(), null))
        captureTiles(
            "tracking_food",
            listOf(
                Item(0, 0, TileSize.LARGE, model(listOf(food, cab)), pictures),
                Item(0, 4, TileSize.MEDIUM, model(listOf(food, cab)), pictures),
                Item(2, 4, TileSize.SMALL, model(listOf(food, cab)), pictures),
            ),
        )
    }

    @Test
    fun arrivingToday() {
        val until = NOW + 6 * 60 * MIN
        val buds = ParcelEntry("buds", amazon.key.packageName, amazon.key.notificationKey, ParcelStage.OUT_FOR_DELIVERY, "Out for delivery", "boAt Airdopes 141", NOW - 30 * MIN, until)
        // Caught earlier; its notification (and picture) is gone, so the app's icon stands in.
        val case = ParcelEntry("case", flipkart.key.packageName, flipkart.key.notificationKey, ParcelStage.ARRIVING_TODAY, "Arriving today by 9 PM", "Phone case", NOW - 3 * 60 * MIN, until)
        val pictures = mapOf(buds.key to TrackImages(product(), null))
        val both = model(parcels = listOf(buds, case))
        captureTiles(
            "tracking_arriving_today",
            listOf(
                Item(0, 0, TileSize.WIDE, both, pictures),
                Item(0, 2, TileSize.MEDIUM, both, pictures),
                Item(2, 2, TileSize.SMALL, both, pictures),
                Item(0, 4, TileSize.LARGE, both, pictures),
            ),
        )
    }

    @Test
    fun nothingOnTheWay() {
        val none = model()
        captureTiles(
            "tracking_nothing",
            listOf(
                Item(0, 0, TileSize.WIDE, none),
                Item(0, 2, TileSize.MEDIUM, none),
                Item(2, 2, TileSize.SMALL, none),
                Item(0, 4, TileSize.WIDE, TrackingModel.of(emptyList(), emptyList(), accessGranted = false, showContent = true, now = NOW)),
            ),
        )
    }

    private fun captureTiles(name: String, items: List<Item>) {
        val icons = IconCache(RuntimeEnvironment.getApplication())
        val cell = (411 - 16 + 4) / 4f
        // Icons load on a background thread the capture doesn't wait for: render them up front.
        for (app in apps.values) for (base in listOf(34f, 18f, 38f * 0.7f, 40f * 0.7f)) {
            icons.load(app, (base * cell / 96f * 3f).roundToInt(), true)
        }
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                        val gap = 4.dp
                        val c = (maxWidth + gap) / 4
                        items.forEachIndexed { i, item ->
                            val tile = ResolvedTile(TileSpec(i + 1L, item.size, TileContent.Tracking), null, "tracking")
                            Box(Modifier.offset(c * item.x, c * item.y).size(c * item.size.width - gap, c * item.size.height - gap)) {
                                TrackingTile(tile, LiveContent.Tracking(item.model, apps, item.images), true, icons, c, editing = false)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- drawn stand-ins for the apps' notification pictures ----

    private fun picture(draw: Canvas.(Paint, Float) -> Unit): Bitmap {
        val s = 240
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(bitmap).draw(paint, s.toFloat())
        return bitmap
    }

    private fun driver() = picture { p, s ->
        drawColor(0xFF6D7F8F.toInt())
        p.color = 0xFF2F3E4C.toInt(); drawOval(RectF(s * 0.12f, s * 0.66f, s * 0.88f, s * 1.3f), p)
        p.color = 0xFFC89B7B.toInt(); drawCircle(s * 0.5f, s * 0.4f, s * 0.19f, p)
        p.color = 0xFF1D1D1D.toInt(); drawArc(RectF(s * 0.31f, s * 0.2f, s * 0.69f, s * 0.5f), 180f, 180f, true, p)
    }

    private fun dish() = picture { p, s ->
        drawColor(0xFF5A2E1A.toInt())
        p.color = 0xFFE9E1D3.toInt(); drawCircle(s / 2, s * 0.53f, s * 0.37f, p)
        p.color = 0xFFE0A43A.toInt(); drawCircle(s / 2, s * 0.53f, s * 0.27f, p)
        p.color = 0xFFB8641E.toInt(); drawCircle(s * 0.4f, s * 0.47f, s * 0.07f, p); drawCircle(s * 0.58f, s * 0.6f, s * 0.08f, p)
        p.color = 0xFF5D8A3A.toInt(); drawCircle(s * 0.57f, s * 0.42f, s * 0.05f, p); drawCircle(s * 0.42f, s * 0.64f, s * 0.05f, p)
    }

    private fun product() = picture { p, s ->
        drawColor(0xFFF2F2F2.toInt())
        p.color = 0xFF222222.toInt(); drawRoundRect(RectF(s * 0.23f, s * 0.3f, s * 0.77f, s * 0.73f), s * 0.2f, s * 0.2f, p)
        p.color = 0xFF33AAEE.toInt(); drawCircle(s * 0.5f, s * 0.63f, s * 0.03f, p)
    }

    private fun vehicle(car: Boolean) = Bitmap.createBitmap(80, 56, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        if (car) {
            c.drawPath(Path().apply { moveTo(6f, 40f); lineTo(6f, 28f); lineTo(16f, 14f); lineTo(60f, 14f); lineTo(72f, 28f); lineTo(72f, 40f); close() }, p)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f
            c.drawPath(Path().apply { moveTo(16f, 42f); lineTo(46f, 42f); lineTo(56f, 18f); lineTo(68f, 18f) }, p)
            p.style = Paint.Style.FILL; c.drawRect(14f, 10f, 36f, 26f, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(18f, 44f, 10f, p); c.drawCircle(62f, 44f, 10f, p)
    }

    private fun car() = vehicle(true)
    private fun scooter() = vehicle(false)

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
