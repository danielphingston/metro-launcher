package io.github.danielphingston.metro

import com.android.billingclient.api.BillingClient
import io.github.danielphingston.metro.data.BillingRetry
import io.github.danielphingston.metro.data.HistoryData
import io.github.danielphingston.metro.data.HistoryRules
import io.github.danielphingston.metro.data.KeywordTarget
import io.github.danielphingston.metro.data.SearchKeyword
import io.github.danielphingston.metro.data.SearchKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFixesTest {
    private val yt = SearchKeyword("yt", KeywordTarget.Web)
    private val g = SearchKeyword("g", KeywordTarget.Assistant)

    @Test
    fun keywordEditsAreKeyedByKeywordNotByIdentity() {
        // The list changed meanwhile (a copy with the same text): the edit still lands on the right entry.
        val list = listOf(yt.copy(), g)
        assertEquals(listOf(yt.copy(enabled = false), g), SearchKeywords.setEnabled(list, "YT", false))
        assertEquals(listOf(g), SearchKeywords.remove(list, "yt"))
        val renamed = SearchKeyword("tube", KeywordTarget.Web)
        assertEquals(listOf(renamed, g), SearchKeywords.upsert(list, "yt", renamed))
    }

    @Test
    fun upsertAddsWhenNewOrWhenTheOriginalIsGone() {
        assertEquals(listOf(g, yt), SearchKeywords.upsert(listOf(g), null, yt))
        assertEquals(listOf(g, yt), SearchKeywords.upsert(listOf(g), "gone", yt))
        assertEquals(listOf(g), SearchKeywords.remove(listOf(g), "gone"))
    }

    @Test
    fun launchCountsOfUninstalledAppsArePruned() {
        val data = HistoryData(
            searches = listOf("cats"),
            launchCounts = mapOf("a" to 3, "b" to 1),
            lastLaunched = mapOf("a" to 10L, "b" to 20L),
        )
        val pruned = HistoryRules.pruneLaunches(data, setOf("a"))
        assertEquals(mapOf("a" to 3), pruned.launchCounts)
        assertEquals(mapOf("a" to 10L), pruned.lastLaunched)
        assertEquals(listOf("cats"), pruned.searches)
    }

    @Test
    fun billingIsNotRetriedWhereItCannotWork() {
        assertFalse(BillingRetry.worthRetrying(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
        assertFalse(BillingRetry.worthRetrying(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED))
        assertTrue(BillingRetry.worthRetrying(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
        assertTrue(BillingRetry.worthRetrying(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED))
    }
}
