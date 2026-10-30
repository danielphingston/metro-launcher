package io.github.danielphingston.metro

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.os.Process
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.ui.search.SearchRow
import io.github.danielphingston.metro.ui.search.ShortcutHit
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.search.topResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchRowsTest {
    private val now = 10L * 24 * 60 * 60 * 1000
    private val day = 24 * 60 * 60 * 1000L

    private fun app(label: String, installTime: Long = 0L) = AppInfo(
        key = AppKey("com.example.${label.lowercase().replace(' ', '.')}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = ComponentName("com.example", "Main"),
        isUninstallable = true,
        installTime = installTime,
        loadIcon = { _, _ -> ColorDrawable(0) },
    )

    private val apps = listOf(app("Calendar"), app("Camera"), app("Google Maps"), app("Maps Go"), app("Messages"))

    private fun rows(query: String, shortcuts: List<ShortcutHit> = emptyList()) =
        searchRows(apps, query, shortcuts, SearchEngine.GOOGLE, now)

    private fun List<SearchRow>.labels() = map {
        when (it) {
            is SearchRow.Caption -> "# ${it.text}"
            is SearchRow.App -> it.app.label
            is SearchRow.Shortcut -> "shortcut ${it.hit.shortLabel}"
            is SearchRow.OpenUrl -> "open ${it.url}"
            is SearchRow.Web -> "web ${it.query}"
            is SearchRow.Item -> "item ${it.item.title}"
            is SearchRow.Keyword -> "keyword ${it.item.title}"
            is SearchRow.RecentSearch -> "recent ${it.text}"
            SearchRow.ClearHistory -> "clear history"
        }
    }

    @Test
    fun groupsAppsThenWeb() {
        assertEquals(
            listOf("# apps", "Maps Go", "Google Maps", "# web", "web map"),
            rows("map").labels(),
        )
    }

    @Test
    fun webIsAlwaysLast() {
        val shortcut = ShortcutHit("com.example.messages", "new", 0, "New message", null)
        assertEquals(
            listOf("# apps", "Messages", "# shortcuts", "shortcut New message", "# web", "web mes"),
            rows("mes", listOf(shortcut)).labels(),
        )
        assertEquals(listOf("# web", "web zzz"), rows("zzz").labels())
    }

    @Test
    fun addressesGetAnOpenRowFirst() {
        assertEquals(
            listOf("open https://example.com/maps", "# web", "web example.com/maps"),
            rows(" example.com/maps ").labels(),
        )
    }

    @Test
    fun showsRecentlyAddedWhenEmpty() {
        val recent = apps + app("Fresh", installTime = now - day)
        val rows = searchRows(recent, "  ", emptyList(), SearchEngine.GOOGLE, now)
        assertEquals(listOf("# recently added", "Fresh"), rows.labels())
        assertTrue(searchRows(apps, "", emptyList(), SearchEngine.GOOGLE, now).isEmpty())
    }

    @Test
    fun submitOpensAnExactOrPrefixAppMatch() {
        assertEquals("Camera", ((topResult(rows("cam")) as SearchRow.App).app.label))
        assertEquals("Calendar", ((topResult(rows("calendar")) as SearchRow.App).app.label))
    }

    @Test
    fun submitFallsBackToTheAddressThenTheWeb() {
        // "maps" is a prefix of "Maps Go", so the app wins; "oogle" only matches inside a label.
        assertTrue(topResult(rows("maps")) is SearchRow.App)
        assertTrue(topResult(rows("oogle")) is SearchRow.Web)
        assertTrue(topResult(rows("calendar.com")) is SearchRow.OpenUrl)
        assertEquals("https://www.google.com/search?q=oogle", (topResult(rows("oogle")) as SearchRow.Web).url)
    }

    @Test
    fun submitDoesNothingWithoutAQuery() {
        assertNull(topResult(rows("")))
    }

    @Test
    fun onlyRowsThatActOnTheTypedTextAreRecordedAsSearches() {
        val gear = io.github.danielphingston.metro.ui.search.SearchGlyphs.gear
        val run = io.github.danielphingston.metro.ui.search.ItemAction.Run { }
        fun item(provider: String) = SearchRow.Item(
            provider,
            io.github.danielphingston.metro.ui.search.SearchItem("k", "t", null, gear, run),
            io.github.danielphingston.metro.ui.search.Placement.TOP,
        )
        val recorded = io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.App(apps[0], 0))
        assertTrue(recorded)
        assertTrue(io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.Web("x", SearchEngine.GOOGLE)))
        assertTrue(io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.OpenUrl("https://a.b", "a.b")))
        assertTrue(io.github.danielphingston.metro.ui.search.recordsSearch(item("ask_assistant")))
        assertTrue(io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.Keyword(item("x").item, false)))
        // Calculator copies, timers, notes, contacts and the history rows are not searches worth keeping.
        listOf("calculator", "quick_actions", "contacts", "whatsapp").forEach {
            assertTrue(it, !io.github.danielphingston.metro.ui.search.recordsSearch(item(it)))
        }
        assertTrue(!io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.RecentSearch("x")))
        assertTrue(!io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.ClearHistory))
        assertTrue(!io.github.danielphingston.metro.ui.search.recordsSearch(SearchRow.Caption("apps")))
    }
}
