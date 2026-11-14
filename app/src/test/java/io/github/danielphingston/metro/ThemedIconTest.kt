package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * After some app updates (Gmail on Android 17) the themed layer of an icon draws nothing. Tiles
 * must then show the coloured icon rather than stay blank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemedIconTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun app(icon: AdaptiveIconDrawable) = AppInfo(
        key = AppKey("com.example.app", "com.example.app.Main", 0L),
        label = "App",
        user = Process.myUserHandle(),
        component = ComponentName("com.example.app", "com.example.app.Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> icon },
        declaresIcon = true,
    )

    @Test
    fun emptyThemedLayerFallsBackToTheColouredIcon() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE), ColorDrawable(Color.TRANSPARENT))
        val cache = IconCache(context)
        val loaded = cache.load(app(icon), 96, monochrome = true)
        assertNotNull(loaded)
        assertFalse(loaded!!.isMonochrome)
        // Not kept, and the next return to the launcher asks the tiles again.
        org.junit.Assert.assertNull(cache.peek(app(icon), 96, monochrome = true))
        val before = cache.generation
        cache.retryFailed()
        assertTrue(cache.generation > before)
        cache.retryFailed()
        assertTrue(cache.generation == before + 1)
    }

    /** An icon that fails on every return is asked for at most five times, until its app changes. */
    @Test
    fun returnRetriesStopAfterACapUntilTheAppChanges() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE), ColorDrawable(Color.TRANSPARENT))
        val cache = IconCache(context)
        val app = app(icon)
        repeat(io.github.danielphingston.metro.data.MAX_RETURN_RETRIES) {
            cache.load(app, 96, monochrome = true)
            val before = cache.generation
            cache.retryFailed()
            assertTrue(cache.generation == before + 1)
        }
        cache.load(app, 96, monochrome = true)
        val capped = cache.generation
        cache.retryFailed()
        assertTrue(cache.generation == capped)
        // The app was updated: retrying on return starts again.
        cache.evict("com.example.app")
        cache.load(app, 96, monochrome = true)
        val afterChange = cache.generation
        cache.retryFailed()
        assertTrue(cache.generation == afterChange + 1)
    }

    @Test
    fun drawnThemedLayerIsUsed() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE), ColorDrawable(Color.BLACK))
        assertTrue(IconCache(context).load(app(icon), 96, monochrome = true)!!.isMonochrome)
    }
}
