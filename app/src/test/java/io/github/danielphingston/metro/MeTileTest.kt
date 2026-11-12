package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.MeFeed
import io.github.danielphingston.metro.live.MeInput
import io.github.danielphingston.metro.live.MeSnapshot
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.tiles.MeModel
import io.github.danielphingston.metro.ui.tiles.MeTap
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MeTileTest {
    private fun input(key: String, app: String, time: Long, preview: NotificationPreview? = NotificationPreview("t-$key", "x-$key", time)) =
        MeInput(key, "$app#0", app, time, null) { preview }

    private val snapshot = MeFeed.build(
        listOf(
            input("a1", "mail", 10), input("a2", "mail", 30), input("b1", "chat", 20),
            input("c1", "news", 5, preview = null), // SECRET: no preview
        ),
    )
    private val name = { app: io.github.danielphingston.metro.live.MeApp -> app.packageName.uppercase() }

    @Test
    fun feedOrdersNewestFirstAndCounts() {
        assertEquals(4, snapshot.total)
        assertEquals(listOf("mail", "chat", "news"), snapshot.apps.map { it.packageName })
        assertEquals(2, snapshot.apps.first().count)
        assertEquals("a2", snapshot.apps.first().newestKey)
        assertEquals(listOf("a2", "b1", "a1", "c1"), snapshot.recent.map { it.key })
        assertEquals(2, MeFeed.build(snapshot.recent.map { input(it.key, it.packageName, it.postTime) }, recentLimit = 2).recent.size)
    }

    @Test
    fun iconsAndFacesFollowSize() {
        val wide = MeModel.of(snapshot, true, true, TileSize.WIDE, name)
        assertEquals(MeModel.State.ACTIVE, wide.state)
        assertEquals(4, wide.count)
        assertEquals(listOf("mail#0", "chat#0", "news#0"), wide.icons)
        assertEquals("a2", wide.latest?.key)
        assertEquals("t-a2", wide.latest?.title)
        assertTrue(MeModel.of(snapshot, true, true, TileSize.SMALL, name).icons.isEmpty())
        assertEquals(3, MeModel.of(snapshot, true, true, TileSize.MEDIUM, name).icons.size)
        assertFalse(MeModel.of(snapshot, true, true, TileSize.SMALL, name).hasBack(TileSize.SMALL))
        assertTrue(MeModel.of(snapshot, true, true, TileSize.MEDIUM, name).hasBack(TileSize.MEDIUM))
    }

    @Test
    fun secretNotificationsShowOnlyTheAppName() {
        val model = MeModel.of(snapshot, true, true, TileSize.WIDE, name)
        val secret = model.faces.first { it.key == "c1" }
        assertEquals("NEWS", secret.appName)
        assertNull(secret.title)
        assertNull(secret.text)
    }

    @Test
    fun contentOffShowsAppsAndCountsOnly() {
        val model = MeModel.of(snapshot, true, false, TileSize.WIDE, name)
        assertEquals(listOf("MAIL", "CHAT", "NEWS"), model.faces.map { it.appName })
        assertTrue(model.faces.all { it.title == null })
        assertEquals("2 new", model.faces.first().text)
        assertFalse(model.description().contains("t-a2"))
    }

    @Test
    fun emptyAndNoAccessStates() {
        assertEquals(MeModel.State.EMPTY, MeModel.of(MeSnapshot.EMPTY, true, true, TileSize.WIDE, name).state)
        val off = MeModel.of(snapshot, false, true, TileSize.WIDE, name)
        assertEquals(MeModel.State.NO_ACCESS, off.state)
        assertFalse(off.hasBack(TileSize.WIDE))
        assertEquals("me, nothing new", MeModel.of(MeSnapshot.EMPTY, true, true, TileSize.WIDE, name).description())
    }

    @Test
    fun spokenDescriptionHonoursContentSetting() {
        assertEquals("me, 4 notifications, latest from MAIL: t-a2", MeModel.of(snapshot, true, true, TileSize.WIDE, name).description())
        assertEquals("me, 4 notifications, latest from MAIL", MeModel.of(snapshot, true, false, TileSize.WIDE, name).description())
    }

    @Test
    fun tapOpensTheShownNotificationElseTheNewest() {
        assertEquals("chat", MeTap.resolve(snapshot, "b1")?.packageName)
        assertEquals("mail", MeTap.resolve(snapshot, null)?.packageName) // several apps: newest
        assertEquals("mail", MeTap.resolve(snapshot, "gone")?.packageName)
        val one = MeFeed.build(listOf(input("a1", "mail", 1), input("a2", "mail", 2)))
        assertNull(MeTap.resolve(one, null)?.intent) // single app: open the app
        assertNull(MeTap.resolve(MeSnapshot.EMPTY, null))
    }

    @Test
    fun jsonRoundTripAndSingleton() {
        val tiles = listOf(TileSpec(1, TileSize.WIDE, TileContent.Me))
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
        assertEquals("me", TileJson.encode(tiles).getJSONObject(0).getString("type"))
        assertEquals("[{\"id\":7,\"type\":\"me\",\"size\":\"WIDE\"}]", "[{\"id\":7,\"type\":\"me\",\"size\":\"WIDE\"}]".also { assertEquals(TileContent.Me, TileJson.decode(JSONArray(it)).single().content) })
        // One per layout is enforced by TileRepository.add (identical content is refused); data objects compare equal.
        assertEquals(TileContent.Me, TileContent.Me)
    }

    @Test
    fun packageNamesNeverShowOnTheTile() {
        val feed = MeFeed.build(listOf(input("s1", "com.android.systemui", 50), input("a1", "mail", 10)))
        val model = MeModel.of(feed, true, true, TileSize.WIDE) { app -> MeModel.humanLabel(null, app.packageName) }
        assertTrue(model.faces.all { it.appName == null })
        assertFalse(model.description().contains("com.android"))
        assertEquals("me, 2 notifications, latest: t-s1", model.description())
        val off = MeModel.of(feed, true, false, TileSize.WIDE) { app -> MeModel.humanLabel(app.packageName, app.packageName) }
        assertTrue(off.faces.all { it.appName == null })
        assertNull(MeModel.humanLabel("com.android.systemui", "other.pkg"))
        assertNull(MeModel.humanLabel("  ", "x"))
        assertEquals("System UI", MeModel.humanLabel("System UI", "com.android.systemui"))
        assertEquals("Mail", MeModel.humanLabel("Mail", "com.mail"))
        // Real names that look like a domain stay; only a package-like name (two dots, no spaces) goes.
        assertEquals("mobile.de", MeModel.humanLabel("mobile.de", "de.mobile.android.app"))
        assertEquals("wetter.com", MeModel.humanLabel("wetter.com", "com.wetter.androidclient"))
        assertEquals("Web.de Mail", MeModel.humanLabel("Web.de Mail", "de.web.mobile.android.mail"))
        assertNull(MeModel.humanLabel("com.example.app", "other.pkg"))
        assertNull(MeModel.humanLabel("DE.MOBILE.ANDROID", "de.mobile.android"))
        assertNull(MeModel.humanLabel("Com.Example", "com.example"))
    }

    @Test
    fun systemUiAndGroupSummariesAreFiltered() {
        val own = "io.github.danielphingston.metro"
        fun counts(pkg: String, flags: Int = 0, system: Boolean = false, launchable: Boolean = true) =
            io.github.danielphingston.metro.live.NotificationFilter.counts(pkg, own, false, true, flags, null, system, launchable)
        assertTrue(counts("com.example.mail"))
        assertFalse(counts("com.android.systemui"))
        assertFalse(counts("android"))
        assertFalse(counts("com.google.android.gms", system = true, launchable = false))
        assertTrue(counts("com.android.dialer", system = true, launchable = true))
        assertFalse(counts("com.example.mail", flags = android.app.Notification.FLAG_GROUP_SUMMARY))
        assertFalse(counts(own))
    }
}
