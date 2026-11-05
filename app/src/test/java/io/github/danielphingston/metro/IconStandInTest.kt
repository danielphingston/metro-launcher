package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.IconCache
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * While an app is being updated the system hands out its default icon instead of the app's.
 * That stand-in must not be cached, or tiles keep the generic icon until the launcher restarts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IconStandInTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun app(declaresIcon: Boolean, icon: () -> Drawable) = AppInfo(
        key = AppKey("com.example.app", "com.example.app.Main", 0L),
        label = "App",
        user = Process.myUserHandle(),
        component = ComponentName("com.example.app", "com.example.app.Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> icon() },
        declaresIcon = declaresIcon,
    )

    @Test
    fun standInIconIsNotCachedWhenTheAppHasItsOwn() {
        val cache = IconCache(context)
        val app = app(declaresIcon = true) { context.packageManager.defaultActivityIcon }
        assertNull(cache.load(app, 96, monochrome = false))
        assertNull(cache.peek(app, 96, monochrome = false))
    }

    @Test
    fun appWithoutItsOwnIconKeepsTheDefault() {
        val cache = IconCache(context)
        assertNotNull(cache.load(app(declaresIcon = false) { context.packageManager.defaultActivityIcon }, 96, false))
    }

    @Test
    fun realIconLoads() {
        val cache = IconCache(context)
        assertNotNull(cache.load(app(declaresIcon = true) { ColorDrawable(Color.RED) }, 96, false))
    }
}
