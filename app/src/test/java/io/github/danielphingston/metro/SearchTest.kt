package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.ui.search.SearchRanking
import io.github.danielphingston.metro.ui.search.WebSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test
    fun ranksExactThenPrefixThenWordStartThenContains() {
        assertEquals(SearchRanking.EXACT, SearchRanking.rank("Maps", "maps"))
        assertEquals(SearchRanking.PREFIX, SearchRanking.rank("Maps Go", "ma"))
        assertEquals(SearchRanking.WORD_START, SearchRanking.rank("Google Maps", "map"))
        assertEquals(SearchRanking.CONTAINS, SearchRanking.rank("Dropbox", "box"))
        assertNull(SearchRanking.rank("Calendar", "map"))
        assertNull(SearchRanking.rank("Calendar", "  "))
    }

    @Test
    fun rankingIgnoresCaseAndAccents() {
        assertEquals(SearchRanking.EXACT, SearchRanking.rank("Café", "CAFE"))
        assertEquals(SearchRanking.PREFIX, SearchRanking.rank("Écran", "ecr"))
        assertEquals(SearchRanking.WORD_START, SearchRanking.rank("My Café", " cafe "))
    }

    @Test
    fun wordStartNeedsALetterBoundary() {
        assertEquals(SearchRanking.WORD_START, SearchRanking.rank("Photo-Editor", "editor"))
        assertEquals(SearchRanking.CONTAINS, SearchRanking.rank("Photoeditor", "editor"))
    }

    @Test
    fun topSortsByRankThenAlphabeticallyAndLimits() {
        val labels = listOf("Dropmap", "Maps", "Atlas Map", "Map", "Mapper", "Calendar")
        val top = SearchRanking.top(labels, "map", 4) { listOf(it) }
        assertEquals(listOf("Map", "Mapper", "Maps", "Atlas Map"), top.map { it.item })
        assertEquals(
            listOf(SearchRanking.EXACT, SearchRanking.PREFIX, SearchRanking.PREFIX, SearchRanking.WORD_START),
            top.map { it.rank },
        )
    }

    @Test
    fun topMatchesAnyLabelAndKeepsTheBest() {
        val shortcuts = listOf("New message" to "Compose a message", "Scan" to "Scan a document")
        val top = SearchRanking.top(shortcuts, "doc", 4) { listOf(it.first, it.second) }
        assertEquals(listOf("Scan"), top.map { it.item.first })
        assertEquals(SearchRanking.WORD_START, top.single().rank)
    }

    @Test
    fun topIsEmptyForABlankQuery() {
        assertTrue(SearchRanking.top(listOf("Maps"), " ", 8) { listOf(it) }.isEmpty())
    }

    @Test
    fun recognisesAddresses() {
        assertTrue(WebSearch.looksLikeUrl("https://example.com"))
        assertTrue(WebSearch.looksLikeUrl("HTTP://Example.com/a?b=c#d"))
        assertTrue(WebSearch.looksLikeUrl("example.com"))
        assertTrue(WebSearch.looksLikeUrl("  www.example.co.uk/path  "))
        assertTrue(WebSearch.looksLikeUrl("sub-domain.example.org:8080/x"))
        assertTrue(WebSearch.looksLikeUrl("bücher.de"))
    }

    @Test
    fun ordinaryTextIsNotAnAddress() {
        assertFalse(WebSearch.looksLikeUrl("weather in paris"))
        assertFalse(WebSearch.looksLikeUrl("example.com is down"))
        assertFalse(WebSearch.looksLikeUrl("maps"))
        assertFalse(WebSearch.looksLikeUrl("3.14"))
        assertFalse(WebSearch.looksLikeUrl("file.t"))
        assertFalse(WebSearch.looksLikeUrl("-bad.com"))
        assertFalse(WebSearch.looksLikeUrl("ftp://example.com"))
        assertFalse(WebSearch.looksLikeUrl(""))
    }

    @Test
    fun bareDomainsGetHttps() {
        assertEquals("https://example.com/a", WebSearch.toUrl(" example.com/a "))
        assertEquals("http://example.com", WebSearch.toUrl("http://example.com"))
    }

    @Test
    fun buildsSearchUrls() {
        assertEquals("https://www.google.com/search?q=cats", SearchEngine.GOOGLE.searchUrl("cats"))
        assertEquals("https://www.bing.com/search?q=cats", SearchEngine.BING.searchUrl("cats"))
        assertEquals("https://duckduckgo.com/?q=cats", SearchEngine.DUCKDUCKGO.searchUrl("cats"))
    }

    @Test
    fun searchUrlsEncodeTheQuery() {
        assertEquals(
            "https://www.google.com/search?q=a+%26+b%3Dc+%C3%A9",
            SearchEngine.GOOGLE.searchUrl("  a & b=c é "),
        )
        assertEquals("https://duckduckgo.com/?q=%22quoted%22+%23tag", SearchEngine.DUCKDUCKGO.searchUrl("\"quoted\" #tag"))
    }
}
