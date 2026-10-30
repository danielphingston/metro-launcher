package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Process
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.ChipEdit
import io.github.danielphingston.metro.data.KeywordError
import io.github.danielphingston.metro.data.KeywordTarget
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SearchKeyword
import io.github.danielphingston.metro.data.SearchKeywords
import io.github.danielphingston.metro.data.WebsiteTemplate
import io.github.danielphingston.metro.ui.search.KeywordRows
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchInPhone
import io.github.danielphingston.metro.ui.search.SearchRow
import io.github.danielphingston.metro.ui.search.ItemAction
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.search.topResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

// org.json, Intent and Uri are Android APIs; Robolectric provides the real ones.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchKeywordsTest {
    private val defaults = SearchKeywords.defaults

    private fun detect(query: String, keywords: List<SearchKeyword> = defaults, active: Boolean = true) =
        SearchKeywords.detect(query, keywords, active)

    // --- detection -------------------------------------------------------------------------

    @Test
    fun aKeywordAndASpaceTrigger() {
        val m = detect("a headphones")!!
        assertEquals("a", m.entry.keyword)
        assertEquals("headphones", m.rest)
        assertEquals(KeywordTarget.BuiltIn(SearchInApp.AMAZON), m.entry.target)
    }

    @Test
    fun caseIsIgnoredAndTheTypedCaseKept() {
        val m = detect("YT Cats")!!
        assertEquals("yt", m.entry.keyword)
        assertEquals("YT", m.typed)
        assertEquals("Cats", m.rest)
    }

    @Test
    fun extraSpacesAfterTheKeywordAreDropped() {
        assertEquals("cats  and dogs", detect("yt    cats  and dogs")!!.rest)
        assertEquals("cats ", detect("yt cats ")!!.rest)
    }

    @Test
    fun theWordAloneNeverTriggers() {
        assertNull(detect("a"))
        assertNull(detect("yt"))
        assertNull(detect("ant"))
        assertNull(detect("ytcats"))
    }

    @Test
    fun aKeywordWithNothingAfterItStillMakesTheChip() {
        val m = detect("a ")!!
        assertEquals("", m.rest)
        assertEquals("", detect("a    ")!!.rest)
    }

    @Test
    fun aLeadingSpaceOrADifferentWordDoesNotTrigger() {
        assertNull(detect(" a cats"))
        assertNull(detect("x cats"))
    }

    @Test
    fun aDisabledKeywordDoesNotTrigger() {
        val list = defaults.map { if (it.keyword == "a") it.copy(enabled = false) else it }
        assertNull(detect("a cats", list))
        assertNotNull(detect("yt cats", list))
    }

    @Test
    fun aLockedOrSwitchedOffFeatureDoesNotTrigger() {
        assertNull(detect("a cats", active = false))
    }

    @Test
    fun theLongestOfOverlappingKeywordsWins() {
        val list = listOf(
            SearchKeyword("a", KeywordTarget.BuiltIn(SearchInApp.AMAZON)),
            SearchKeyword("am", KeywordTarget.Web),
        )
        assertEquals("am", detect("am cats", list)!!.entry.keyword)
        assertEquals("a", detect("a cats", list)!!.entry.keyword)
        // with the longer one off, "am cats" is not "a" + "m cats": the space must follow the keyword
        assertNull(detect("am cats", list.map { if (it.keyword == "am") it.copy(enabled = false) else it }))
    }

    @Test
    fun theQueryIsRebuiltFromTheChip() {
        val m = detect("YT cats")!!
        assertEquals("YT dogs", SearchKeywords.withRest(m, "dogs"))
        assertEquals("YTcats", SearchKeywords.withoutChip(m))
        assertEquals("a", SearchKeywords.withoutChip(detect("a ")!!))
    }

    // --- the chip's text field -------------------------------------------------------------

    @Test
    fun backspaceAtTheStartOfTheTextRemovesTheChip() {
        val g = SearchKeywords.GUARD
        assertEquals(ChipEdit.Remove, SearchKeywords.interpret("", ""))
        assertEquals(ChipEdit.Remove, SearchKeywords.interpret("cats", "cats"))
        assertEquals(ChipEdit.Text("cat"), SearchKeywords.interpret("cats", "${g}cat"))
        assertEquals(ChipEdit.Text("cats!"), SearchKeywords.interpret("cats", "${g}cats!"))
        // select-all and delete wipes the text but is not a backspace at the start
        assertEquals(ChipEdit.Text(""), SearchKeywords.interpret("cats", ""))
        assertEquals(ChipEdit.Text("hi"), SearchKeywords.interpret("cats", "hi"))
    }

    // --- keyword rules ---------------------------------------------------------------------

    @Test
    fun keywordRules() {
        assertNull(SearchKeywords.validate("a1", defaults.drop(1)))
        assertNull(SearchKeywords.validate("abcdefghij", defaults))
        assertEquals(KeywordError.Empty, SearchKeywords.validate("  ", defaults))
        assertEquals(KeywordError.Invalid, SearchKeywords.validate("abcdefghijk", defaults))
        assertEquals(KeywordError.Invalid, SearchKeywords.validate("a b", defaults))
        assertEquals(KeywordError.Invalid, SearchKeywords.validate("a-b", defaults))
        assertTrue(SearchKeywords.validate("YT", defaults) is KeywordError.Taken)
        assertTrue(SearchKeywords.validate("a", defaults) is KeywordError.Taken)
        // editing may keep its own text, in any case
        assertNull(SearchKeywords.validate("YT", defaults, editing = "yt"))
        assertTrue(SearchKeywords.validate("a", defaults, editing = "yt") is KeywordError.Taken)
    }

    @Test
    fun defaultsMatchTheSpec() {
        assertEquals(
            listOf("a", "yt", "ps", "m", "sp", "g", "ai"),
            defaults.map { it.keyword },
        )
        assertEquals(
            listOf(
                KeywordTarget.BuiltIn(SearchInApp.AMAZON), KeywordTarget.BuiltIn(SearchInApp.YOUTUBE),
                KeywordTarget.BuiltIn(SearchInApp.PLAY_STORE), KeywordTarget.BuiltIn(SearchInApp.MAPS),
                KeywordTarget.BuiltIn(SearchInApp.SPOTIFY), KeywordTarget.Web, KeywordTarget.Assistant,
            ),
            defaults.map { it.target },
        )
        assertTrue(defaults.all { it.enabled })
        assertEquals(defaults, LauncherSettings().searchKeywords)
        assertEquals("a → Amazon", defaults.first().summary)
    }

    // --- website templates -----------------------------------------------------------------

    @Test
    fun templateValidation() {
        WebsiteTemplate.presets.forEach { assertNull(it.name, WebsiteTemplate.validate(it.template)) }
        assertNull(WebsiteTemplate.validate("http://example.com/q/%s"))
        assertNull(WebsiteTemplate.validate("  HTTPS://example.com/?q=%s  "))
        assertNotNull(WebsiteTemplate.validate(""))
        assertNotNull(WebsiteTemplate.validate("example.com/?q=%s"))
        assertNotNull(WebsiteTemplate.validate("ftp://example.com/?q=%s"))
        assertNotNull(WebsiteTemplate.validate("https://example.com/search"))
        assertNotNull(WebsiteTemplate.validate("https://example.com/?q=%s&r=%s"))
        assertNotNull(WebsiteTemplate.validate("https://%s.example.com/"))
        assertNotNull(WebsiteTemplate.validate("https:///?q=%s"))
        assertNotNull(WebsiteTemplate.validate("https://example.com/?q=%s x"))
    }

    @Test
    fun templatesEncodeTheText() {
        val flipkart = "https://www.flipkart.com/search?q=%s"
        assertEquals("https://www.flipkart.com/search?q=red%20shoes", WebsiteTemplate.build(flipkart, "red shoes"))
        assertEquals("https://www.flipkart.com/search?q=a%26b%3Dc%2F%25%20%C3%A9", WebsiteTemplate.build(flipkart, "a&b=c/% é"))
        assertEquals("https://x.org/w/safe-text_1.~", WebsiteTemplate.build("https://x.org/w/%s", "safe-text_1.~"))
        assertNull(WebsiteTemplate.build("https://x.org/", "cats"))
        assertEquals("flipkart.com", WebsiteTemplate.hostOf(flipkart.replace("www.", "")))
        assertEquals("flipkart.com", WebsiteTemplate.hostOf(flipkart))
    }

    // --- storage ---------------------------------------------------------------------------

    @Test
    fun jsonRoundTrips() {
        val all = defaults + listOf(
            SearchKeyword("fk", KeywordTarget.Website("Flipkart", "https://www.flipkart.com/search?q=%s"), enabled = false),
            SearchKeyword("kp", KeywordTarget.InstalledApp("com.example.keep", "Keep Notes")),
        )
        assertEquals(all, SearchKeywords.decode(SearchKeywords.encode(all)))
        assertEquals(emptyList<SearchKeyword>(), SearchKeywords.decode(SearchKeywords.encode(emptyList())))
    }

    @Test
    fun decodingIsTolerant() {
        assertEquals(defaults, SearchKeywords.decode(null))
        assertEquals(defaults, SearchKeywords.decode("not json"))
        assertEquals(defaults, SearchKeywords.decode("{\"a\":1}"))
        val json = """[
            {"k":"ok","on":true,"t":{"type":"web"}},
            {"k":"OK","t":{"type":"assistant"}},
            {"k":"bad key","t":{"type":"web"}},
            {"k":"toolongkeyword","t":{"type":"web"}},
            {"k":"nt","t":{"type":"mystery"}},
            {"k":"na","t":{"type":"app","id":"nope"}},
            {"k":"ns","t":{"type":"site","name":"X","template":"https://x.org/"}},
            {"k":"np","t":{"type":"installed","pkg":""}},
            {"t":{"type":"web"}},
            "junk", 7, null,
            {"k":"off","on":false,"t":{"type":"app","id":"maps"}},
            {"k":"site","t":{"type":"site","template":"https://www.example.com/s?q=%s"}}
        ]"""
        val decoded = SearchKeywords.decode(json)
        assertEquals(listOf("ok", "off", "site"), decoded.map { it.keyword })
        assertEquals(KeywordTarget.Web, decoded[0].target)
        assertFalse(decoded[1].enabled)
        assertEquals(KeywordTarget.BuiltIn(SearchInApp.MAPS), decoded[1].target)
        assertEquals(KeywordTarget.Website("example.com", "https://www.example.com/s?q=%s"), decoded[2].target)
    }

    // --- the row and Enter -----------------------------------------------------------------

    private val apps = listOf(
        AppInfo(
            key = AppKey("com.amazon.mShop.android.shopping", "Main", 0),
            label = "Amazon Shopping",
            user = Process.myUserHandle(),
            component = ComponentName("com.amazon.mShop.android.shopping", "Main"),
            isUninstallable = true,
            loadIcon = { _, _ -> ColorDrawable(0) },
        ),
    )

    private class Phone(val resolvable: (Intent) -> Boolean = { true }, val domain: String = "amazon.in") : SearchInPhone {
        override fun resolves(intent: Intent) = resolvable(intent)
        override fun amazonDomain(locale: Locale) = domain
    }

    private fun ctx(settings: LauncherSettings = LauncherSettings()) =
        SearchContext(RuntimeEnvironment.getApplication(), apps, settings, Locale.ENGLISH)

    private fun row(query: String, keywords: List<SearchKeyword> = defaults, phone: SearchInPhone = Phone()) =
        detect(query, keywords)?.let { KeywordRows.build(it, ctx(), phone) }

    private fun intentOf(row: io.github.danielphingston.metro.ui.search.KeywordRow) = (row.item.action as ItemAction.Start).intent

    @Test
    fun amazonUsesTheCountryDomainAndFallsBackToTheBrowser() {
        val app = row("a usb c cable")!!
        assertEquals("search Amazon for “usb c cable”", app.item.title)
        assertFalse(app.isWeb)
        assertEquals("https://www.amazon.in/s?k=usb%20c%20cable", intentOf(app).data.toString())
        assertEquals("com.amazon.mShop.android.shopping", intentOf(app).`package`)
        val web = row("a usb", phone = Phone({ it.`package` == null }, "amazon.de"))!!
        assertEquals("https://www.amazon.de/s?k=usb", intentOf(web).data.toString())
        assertNull(intentOf(web).`package`)
    }

    @Test
    fun aBuiltInAppThatIsMissingGivesNoRow() {
        assertNull(row("m pizza", phone = Phone({ false })))
        assertNotNull(row("yt cats", phone = Phone({ false }))) // YouTube falls back to its website
    }

    @Test
    fun webAssistantAndInstalledAppTargets() {
        val web = KeywordRows.build(detect("g cats")!!, ctx(LauncherSettings(searchEngine = SearchEngine.DUCKDUCKGO)), Phone())!!
        assertTrue(web.isWeb)
        assertEquals("search the web for “cats”", web.item.title)
        assertTrue(web.item.action is ItemAction.Open)

        // no assistant app installed: nothing to ask
        assertNull(row("ai why is the sky blue"))
        val withAssistant = apps + apps.first().let {
            AppInfo(AppKey("com.anthropic.claude", "Main", 0), "Claude", it.user, it.component, true, loadIcon = it.loadIcon)
        }
        val ask = KeywordRows.build(detect("ai hello")!!, SearchContext(RuntimeEnvironment.getApplication(), withAssistant, LauncherSettings(), Locale.ENGLISH), Phone())!!
        assertEquals("ask Claude", ask.item.title)
        assertEquals("hello", ask.item.subtitle)

        val keep = listOf(SearchKeyword("kp", KeywordTarget.InstalledApp("com.example.keep", "Keep Notes")))
        val installed = row("kp milk", keep)!!
        assertEquals("search Keep Notes for “milk”", installed.item.title)
        assertEquals(Intent.ACTION_SEARCH, intentOf(installed).action)
        val webSearch = row("kp milk", keep, Phone({ it.action == Intent.ACTION_WEB_SEARCH }))!!
        assertEquals(Intent.ACTION_WEB_SEARCH, intentOf(webSearch).action)
        assertNull(row("kp milk", keep, Phone({ false })))
    }

    @Test
    fun aWebsiteOpensItsEncodedAddress() {
        val list = listOf(SearchKeyword("fk", KeywordTarget.Website("Flipkart", WebsiteTemplate.presets[0].template)))
        val r = row("fk red shoes", list)!!
        assertEquals("search Flipkart for “red shoes”", r.item.title)
        val intent = intentOf(r)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.flipkart.com/search?q=red%20shoes", intent.data.toString())
        assertTrue(intent.categories.contains(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun nothingAfterTheKeywordGivesNoRow() {
        assertNull(row("a "))
    }

    @Test
    fun theKeywordRowIsFirstAndTheWebRowStaysBelowIt() {
        val keyword = row("a cats")!!
        val rows = searchRows(apps, "cats", emptyList(), SearchEngine.GOOGLE, 0L, keyword = keyword)
        assertTrue(rows.first() is SearchRow.Keyword)
        assertEquals(1, rows.count { it is SearchRow.Web })
        assertTrue(rows.indexOfFirst { it is SearchRow.Web } > 0)
        // Enter runs the keyword, not the web search
        assertTrue(topResult(rows) is SearchRow.Keyword)
        // and without a keyword Enter still goes to the web
        assertTrue(topResult(searchRows(apps, "cats", emptyList(), SearchEngine.GOOGLE, 0L)) is SearchRow.Web)
    }

    @Test
    fun theWebKeywordIsTheWebRowNotASecondCopy() {
        val keyword = row("g cats")!!
        val rows = searchRows(apps, "cats", emptyList(), SearchEngine.GOOGLE, 0L, keyword = keyword)
        assertTrue(rows.first() is SearchRow.Keyword)
        assertEquals(0, rows.count { it is SearchRow.Web })
        assertTrue(topResult(rows) is SearchRow.Keyword)
    }

    @Test
    fun otherResultsStillAppearForTheRest() {
        val rows = searchRows(apps, "amazon", emptyList(), SearchEngine.GOOGLE, 0L, keyword = row("a amazon"))
        assertEquals(
            listOf("keyword", "caption", "app", "caption", "web"),
            rows.map {
                when (it) {
                    is SearchRow.Keyword -> "keyword"
                    is SearchRow.Caption -> "caption"
                    is SearchRow.App -> "app"
                    is SearchRow.Web -> "web"
                    else -> "other"
                }
            },
        )
    }
}
