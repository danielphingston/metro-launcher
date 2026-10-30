package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.search.SearchRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smarter app matching: initials, typos and the order between the ranks. */
class SmartMatchingTest {
    @Test
    fun ranksAreOrderedExactPrefixWordStartInitialsContainsFuzzy() {
        val ranks = listOf(
            SearchRanking.EXACT, SearchRanking.PREFIX, SearchRanking.WORD_START,
            SearchRanking.INITIALS, SearchRanking.CONTAINS, SearchRanking.FUZZY,
        )
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.size, ranks.toSet().size)
    }

    @Test
    fun initialsMatchWordsAndCamelCase() {
        assertEquals(SearchRanking.INITIALS, SearchRanking.rank("YouTube", "yt"))
        assertEquals(SearchRanking.INITIALS, SearchRanking.rank("Google Maps", "gm"))
        assertEquals(SearchRanking.INITIALS, SearchRanking.rank("Google Maps Go", "gm"))
        assertEquals(SearchRanking.INITIALS, SearchRanking.rank("Google Maps Go", "gmg"))
        assertEquals(SearchRanking.INITIALS, SearchRanking.rank("Photo-Editor Pro", "pep"))
    }

    @Test
    fun initialsNeedTwoLettersAndTwoWords() {
        // One letter is a plain prefix or contains match, never initials; one word has no initials.
        assertEquals(SearchRanking.PREFIX, SearchRanking.rank("Google Maps", "g"))
        assertNull(SearchRanking.rank("Camera", "cm"))
    }

    @Test
    fun initialsLoseToPrefixAndWordStart() {
        // "ma" is a prefix of "Maps" and the start of the second word of "Google Maps"; neither is "initials".
        assertEquals(SearchRanking.PREFIX, SearchRanking.rank("Maps", "ma"))
        assertEquals(SearchRanking.WORD_START, SearchRanking.rank("Google Maps", "ma"))
    }

    @Test
    fun containsBeatsFuzzy() {
        assertEquals(SearchRanking.CONTAINS, SearchRanking.rank("Dropbox", "pbox"))
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Dropbox", "dropbix"))
    }

    @Test
    fun oneTypoIsFineForFourToSevenLetters() {
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Chrome", "chorme")) // swapped neighbours
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Calendar", "calender")) // substituted letter
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Camera", "camra")) // missing letter
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Camera", "camerra")) // extra letter
        assertNull(SearchRanking.rank("Camera", "cmra")) // two slips are too many at 4 letters
        assertNull(SearchRanking.rank("Camera", "kamora"))
    }

    @Test
    fun twoTyposAreFineFromEightLetters() {
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Calendar", "calnedar")) // one swap, still within one
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Spotify Music", "spotfy musc"))
        assertNull(SearchRanking.rank("Calendar", "kalindar x"))
    }

    @Test
    fun shortQueriesAreNeverFuzzy() {
        assertNull(SearchRanking.rank("Maps", "mpa"))
        assertNull(SearchRanking.rank("Gmail", "gmx"))
        assertEquals(0, SearchRanking.allowedTypos(3))
        assertEquals(1, SearchRanking.allowedTypos(4))
        assertEquals(1, SearchRanking.allowedTypos(7))
        assertEquals(2, SearchRanking.allowedTypos(8))
    }

    @Test
    fun typosMatchOneWordOrTheStartOfOne() {
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Google Chrome", "chorme"))
        // "chrom" typed so far, with a slip: the start of a word still counts.
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Google Chrome", "chorm"))
    }

    @Test
    fun editDistanceCountsASwapAsOne() {
        assertEquals(0, SearchRanking.editDistance("maps", "maps"))
        assertEquals(1, SearchRanking.editDistance("maps", "mpas"))
        assertEquals(1, SearchRanking.editDistance("maps", "map"))
        assertEquals(2, SearchRanking.editDistance("maps", "mop"))
        assertEquals(4, SearchRanking.editDistance("", "maps"))
    }

    @Test
    fun accentsAndCaseStillFold() {
        assertEquals(SearchRanking.FUZZY, SearchRanking.rank("Café Müller", "cafe muller".replace("muller", "mulller")))
        assertEquals(SearchRanking.INITIALS, SearchRanking.rank("Éditeur Photo", "EP"))
    }

    @Test
    fun wordsSplitOnPunctuationAndHumps() {
        assertEquals(listOf("you", "tube"), SearchRanking.words("YouTube"))
        assertEquals(listOf("google", "maps"), SearchRanking.words("Google  Maps"))
        assertEquals(listOf("photo", "editor"), SearchRanking.words("Photo-Editor"))
        assertTrue(SearchRanking.words("  ").isEmpty())
    }

    @Test
    fun topKeepsTheNewRanksInOrder() {
        val labels = listOf("Chrome", "Chromecast", "Google Chrome", "Archrome", "Chorme Beta")
        val top = SearchRanking.top(labels, "chrome", 5) { listOf(it) }
        assertEquals(listOf("Chrome", "Chromecast", "Google Chrome", "Archrome", "Chorme Beta"), top.map { it.item })
        assertEquals(
            listOf(SearchRanking.EXACT, SearchRanking.PREFIX, SearchRanking.WORD_START, SearchRanking.CONTAINS, SearchRanking.FUZZY),
            top.map { it.rank },
        )
    }
}
