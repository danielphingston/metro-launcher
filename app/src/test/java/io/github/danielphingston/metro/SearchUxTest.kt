package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Process
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.HistoryRules
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ListFilter
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchInOrder
import io.github.danielphingston.metro.data.SearchInOrdering
import io.github.danielphingston.metro.data.SearchInTarget
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.search.ContactsProvider
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.search.SearchIcon
import io.github.danielphingston.metro.ui.search.SearchInApps
import io.github.danielphingston.metro.ui.search.SearchInAppsProvider
import io.github.danielphingston.metro.ui.search.SearchInPhone
import io.github.danielphingston.metro.ui.search.WhatsAppApps
import io.github.danielphingston.metro.ui.search.WhatsAppRow
import io.github.danielphingston.metro.ui.settings.keywordsSummary
import io.github.danielphingston.metro.ui.settings.searchInSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** The search-in strip's order, its stored custom order, the settings filter and the app icons the rows use. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SearchUxTest {
    private val yt = SearchInTarget("youtube", "YouTube")
    private val ps = SearchInTarget("play_store", "Play Store")
    private val maps = SearchInTarget("maps", "Maps")
    private val keep = SearchInTarget("app:com.example.keep", "Keep")
    private val natural = listOf(yt, ps, maps, keep)

    private fun ids(list: List<SearchInTarget>) = list.map { it.id }

    @Test
    fun mostUsedOrdersByCountThenByCustomOrder() {
        val uses = mapOf("maps" to 5, "app:com.example.keep" to 5, "play_store" to 1)
        // Maps and Keep tie on 5; the custom order puts Keep first. Unused YouTube comes last.
        val custom = listOf("app:com.example.keep", "maps")
        assertEquals(
            listOf("app:com.example.keep", "maps", "play_store", "youtube"),
            ids(SearchInOrdering.ordered(natural, SearchInOrder.MOST_USED, custom, uses)),
        )
    }

    @Test
    fun mostUsedWithNoCountsFallsBackToTheCustomThenNaturalOrder() {
        assertEquals(ids(natural), ids(SearchInOrdering.ordered(natural, SearchInOrder.MOST_USED, emptyList(), emptyMap())))
        assertEquals(
            listOf("maps", "youtube", "play_store", "app:com.example.keep"),
            ids(SearchInOrdering.ordered(natural, SearchInOrder.MOST_USED, listOf("maps"), emptyMap())),
        )
    }

    @Test
    fun customOrderIgnoresCountsAndKeepsUnknownTargetsLastInNaturalOrder() {
        val uses = mapOf("youtube" to 99)
        assertEquals(
            listOf("app:com.example.keep", "youtube", "play_store", "maps"),
            ids(SearchInOrdering.ordered(natural, SearchInOrder.CUSTOM, listOf("app:com.example.keep", "gone", "youtube"), uses)),
        )
    }

    @Test
    fun usesAreCountedPerTarget() {
        val once = HistoryRules.addUse(emptyMap(), "youtube")
        assertEquals(mapOf("youtube" to 2, "maps" to 1), HistoryRules.addUse(HistoryRules.addUse(once, "youtube"), "maps"))
    }

    @Test
    fun customOrderEncodesDecodesAndCompletes() {
        val ids = listOf("maps", "app:com.example.keep", "youtube")
        assertEquals(ids, SearchInOrdering.decode(SearchInOrdering.encode(ids)))
        assertEquals(emptyList<String>(), SearchInOrdering.decode(null))
        assertEquals(listOf("a", "b"), SearchInOrdering.decode(" a \n\n b\na"))
        // Uninstalled targets drop out; new ones are added after the listed ones.
        assertEquals(listOf("maps", "youtube", "play_store"), SearchInOrdering.complete(listOf("maps", "gone", "youtube"), listOf("youtube", "play_store", "maps")))
    }

    @Test
    fun movingStopsAtTheEnds() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), SearchInOrdering.move(ids, "b", -1))
        assertEquals(listOf("a", "c", "b"), SearchInOrdering.move(ids, "b", 1))
        assertEquals(ids, SearchInOrdering.move(ids, "a", -1))
        assertEquals(ids, SearchInOrdering.move(ids, "c", 1))
        assertEquals(ids, SearchInOrdering.move(ids, "zzz", 1))
    }

    @Test
    fun customOrderSurvivesInTheSettings() {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        val known = listOf("youtube", "play_store", "maps")
        runBlocking {
            assertEquals(SearchInOrder.MOST_USED, repository.settings.first().searchInOrder)
            repository.setSearchInOrder(SearchInOrder.CUSTOM)
            repository.moveSearchInTarget("maps", -2, known)
            val saved = repository.settings.first()
            assertEquals(SearchInOrder.CUSTOM, saved.searchInOrder)
            assertEquals(listOf("maps", "youtube", "play_store"), saved.searchInCustomOrder)
        }
    }

    @Test
    fun filterMatchesWordsAnywhereIgnoringCase() {
        assertTrue(ListFilter.matches("", "YouTube"))
        assertTrue(ListFilter.matches("  ", "YouTube"))
        assertTrue(ListFilter.matches("tube", "YouTube"))
        assertTrue(ListFilter.matches("PLAY sto", "Play Store"))
        assertTrue(ListFilter.matches("keep", "Notes", "com Keep"))
        assertFalse(ListFilter.matches("maps", "YouTube"))
        assertFalse(ListFilter.matches("play maps", "Play Store"))
    }

    @Test
    fun summariesCountWhatIsOn() {
        val settings = LauncherSettings(searchInApps = setOf(SearchInApp.YOUTUBE, SearchInApp.MAPS), searchInOtherApps = setOf("x"))
        assertEquals("search in apps · 3 on", searchInSummary(settings))
        assertEquals("search keywords · ${settings.searchKeywords.count { it.enabled }} on", keywordsSummary(settings))
    }

    // --- icons ----------------------------------------------------------------------------------

    private fun app(label: String, pkg: String) = AppInfo(
        key = AppKey(pkg, "Main", 0), label = label, user = Process.myUserHandle(),
        component = ComponentName(pkg, "Main"), isUninstallable = true, loadIcon = { _, _ -> ColorDrawable(0) },
    )

    private object Phone : SearchInPhone {
        override fun resolves(intent: Intent) = true
        override fun amazonDomain(locale: Locale) = "amazon.in"
    }

    @Test
    fun amazonsIndiaAppGetsItsOwnIconAndIntent() {
        val india = app("Amazon", "in.amazon.mShop.android.shopping")
        assertEquals(SearchIcon.App(india), SearchInApps.icon(listOf(india), SearchInApp.AMAZON))
        assertEquals(SearchGlyphs.search, SearchInApps.icon(emptyList(), SearchInApp.AMAZON))
        val intent = SearchInApps.intentFor(SearchInApp.AMAZON, "x", object : SearchInPhone by Phone {
            override fun resolves(intent: Intent) = intent.`package` == "in.amazon.mShop.android.shopping"
        }, Locale.ENGLISH)!!
        assertEquals("in.amazon.mShop.android.shopping", intent.`package`)
    }

    @Test
    fun stripUsesTheUsageOrderAndIconsOfInstalledApps() {
        val youtube = app("YouTube", "com.google.android.youtube")
        val settings = LauncherSettings(searchInApps = setOf(SearchInApp.YOUTUBE, SearchInApp.PLAY_STORE))
        val ctx = SearchContext(RuntimeEnvironment.getApplication(), listOf(youtube), settings, Locale.ENGLISH, searchInUses = mapOf("play_store" to 3))
        val strip = runBlocking { SearchInAppsProvider({ Phone }, { _, _ -> emptyList() }).results("cats", ctx) }.single()
        assertEquals("search “cats” in", strip.title)
        assertEquals(listOf("play_store", "youtube"), strip.chips.map { it.id })
        assertEquals(SearchGlyphs.search, strip.chips[0].icon)
        assertEquals(SearchIcon.App(youtube), strip.chips[1].icon)
        assertEquals("search YouTube for cats", strip.chips[1].description)
    }

    @Test
    fun whatsAppButtonUsesTheInstalledAppsIconElseAChatGlyph() {
        val personal = app("WhatsApp", WhatsAppApps.PERSONAL)
        val business = app("WhatsApp Business", WhatsAppApps.BUSINESS)
        assertEquals(SearchIcon.App(personal), WhatsAppApps.icon(listOf(business, personal)))
        assertEquals(SearchIcon.App(business), WhatsAppApps.icon(listOf(business), WhatsAppApps.PERSONAL))
        assertSame(SearchGlyphs.chat, WhatsAppApps.icon(emptyList()))
        val button = ContactsProvider.buttonsFor("0170", WhatsAppRow(1, WhatsAppApps.profileMimeTypes[1]), WhatsAppApps.icon(listOf(business))).last()
        assertEquals(SearchIcon.App(business), button.icon)
    }
}
