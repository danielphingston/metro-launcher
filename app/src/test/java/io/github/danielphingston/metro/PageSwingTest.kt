package io.github.danielphingston.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.ui.start.continuum
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The swing between start and the app list must never draw the start page over the app list:
 * with no right padding its tiles reach its edge, and anything past that edge showed as a strip.
 * The start page is painted solid red here and the app list left empty; while the pager sits
 * part way, no red may appear right of the start page's edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PageSwingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLeavingStartPageStaysLeftOfItsEdge() {
        val pager = PagerState(currentPage = 0) { 2 }
        compose.setContent {
            Box(Modifier.size(400.dp, 800.dp).background(Color.White).testTag("root")) {
                HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .continuum(pager, page)
                            .then(if (page == 0) Modifier.background(Color.Red) else Modifier),
                    )
                }
            }
        }
        // Current page 1 with offset -f: the start page sits at offset -(1 - f), its right edge f of the way in.
        for (f in listOf(0.45f, 0.3f, 0.15f, 0.05f, 0f)) {
            runBlocking { compose.runOnUiThread { runBlocking { pager.scrollToPage(1, -f) } } }
            compose.waitForIdle()
            val image = snapshot()
            val edge = (image.width * f).toInt()
            var red = 0
            for (x in (edge + 2) until image.width) for (y in 0 until image.height step 8) {
                val p = image.getPixel(x, y)
                if (android.graphics.Color.red(p) > 200 && android.graphics.Color.green(p) < 80) red++
            }
            assertEquals("start page drawn past its edge with the pager ${1 - f} of the way to the app list", 0, red)
        }
    }

    /** The root box as software-drawn pixels (Compose's own capture needs a real window). */
    private fun snapshot(): android.graphics.Bitmap {
        val bounds = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInWindow
        val view = compose.activity.window.decorView
        val full = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(full)) }
        return android.graphics.Bitmap.createBitmap(
            full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt(),
        )
    }
}
