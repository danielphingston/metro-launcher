package io.github.danielphingston.metro

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.os.Process
import android.os.UserManager
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.ui.tiles.WidgetRestore
import io.github.danielphingston.metro.ui.tiles.WidgetRestore.Offer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Setting up a widget tile whose widget is gone, as after restoring a backup made on another phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetRestoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val maps = ComponentName("com.google.android.apps.maps", "com.google.android.apps.maps.TrafficWidget")
    private val widget = TileContent.Widget(appWidgetId = 41, provider = maps.flattenToString(), userSerial = 0L)

    @Test
    fun whatATapOffers() {
        // The widget's provider is on the phone: create it again, whatever else is true.
        assertEquals(Offer.RECREATE, WidgetRestore.offer(providerInstalled = true, appInstalled = true, storeOpensLink = true))
        assertEquals(Offer.RECREATE, WidgetRestore.offer(providerInstalled = true, appInstalled = true, storeOpensLink = false))
        // Its app isn't installed: the store, if a store app opens its page, else only removal.
        assertEquals(Offer.STORE_OR_REMOVE, WidgetRestore.offer(providerInstalled = false, appInstalled = false, storeOpensLink = true))
        assertEquals(Offer.REMOVE, WidgetRestore.offer(providerInstalled = false, appInstalled = false, storeOpensLink = false))
        // Its app is installed without that widget: never another widget of the app instead, only removal.
        assertEquals(Offer.REMOVE, WidgetRestore.offer(providerInstalled = false, appInstalled = true, storeOpensLink = true))
    }

    private fun ownUserHasSerialZero() {
        shadowOf(context.getSystemService(UserManager::class.java)).setSerialNumberForUser(Process.myUserHandle(), 0L)
    }

    private fun installProvider() {
        val info = AppWidgetProviderInfo().apply { provider = maps }
        shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(Process.myUserHandle(), info)
    }

    private fun installApp() =
        shadowOf(context.getSystemService(LauncherApps::class.java)).addEnabledPackage(Process.myUserHandle(), maps.packageName)

    private fun installStore() {
        val store = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.vending"; name = "Store" } }
        shadowOf(context.packageManager).addResolveInfoForIntent(WidgetRestore.storeLink(maps.packageName), store)
    }

    @Test
    fun theProviderOnThePhoneIsCreatedAgain() {
        ownUserHasSerialZero()
        installProvider()
        installApp()
        val found = WidgetRestore.diagnose(context, widget)
        assertEquals(Offer.RECREATE, found.offer)
        assertEquals(maps, found.provider?.provider)
        assertEquals(Process.myUserHandle(), found.user)
    }

    @Test
    fun aMissingAppIsOfferedInTheStoreOnlyWhenAStoreOpensTheLink() {
        ownUserHasSerialZero()
        assertEquals(Offer.REMOVE, WidgetRestore.diagnose(context, widget).offer)
        installStore()
        WidgetRestore.diagnose(context, widget).let {
            assertEquals(Offer.STORE_OR_REMOVE, it.offer)
            assertEquals(maps.packageName, it.packageName)
        }
    }

    @Test
    fun anInstalledAppWithoutTheWidgetCanOnlyBeRemoved() {
        ownUserHasSerialZero()
        installApp()
        installStore()
        assertEquals(Offer.REMOVE, WidgetRestore.diagnose(context, widget).offer)
    }

    @Test
    fun aDamagedOrForeignTileIsNeverOfferedMoreThanRemoval() {
        ownUserHasSerialZero()
        installStore()
        // No provider name to look up, or a user (profile) this phone doesn't have.
        assertEquals(Offer.REMOVE, WidgetRestore.diagnose(context, widget.copy(provider = "not a component")).offer)
        assertEquals(Offer.REMOVE, WidgetRestore.diagnose(context, widget.copy(userSerial = 99L)).offer)
    }

    @Test
    fun aWidgetCreatedAgainKeepsItsTilesPlaceAndSize() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val tiles = TileRepository(context, scope, flowOf(4))
            tiles.tiles.filterNotNull().first()
            val id = checkNotNull(tiles.add(widget, TileSize.WIDE, TileSpan(4, 2)))
            val before = tiles.tiles.value.orEmpty().single { it.id == id }
            assertEquals(41, tiles.replaceWidget(id, 77))
            val after = tiles.tiles.value.orEmpty().single { it.id == id }
            assertEquals(widget.copy(appWidgetId = 77), after.content)
            assertEquals(before.copy(content = after.content), after)
            // Only widget tiles: anything else is left alone.
            val app = checkNotNull(tiles.add(TileContent.Weather, TileSize.MEDIUM))
            assertNull(tiles.replaceWidget(app, 5))
            assertNull(tiles.replaceWidget(-1, 5))
        } finally {
            scope.cancel()
        }
    }
}
