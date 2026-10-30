package io.github.danielphingston.metro.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A provider's rows, in the order the overlay shows them. */
internal class ProvidedItems(val provider: SearchProvider, val items: List<SearchItem>)

/** [items] were produced for [query]; kept so rows of an older query are never shown. */
internal class TaggedItems(val query: String, val items: List<SearchItem>)

/** Which provider results may be shown for the current query. Pure, so the no-stale-rows rule is tested. */
internal object ProviderResults {
    /**
     * Whether [old] and [now] are one piece of typing: one is the start of the other (typing on or
     * deleting back), ignoring case. Rows made for [old] may then stay on screen until the answer
     * for [now] arrives; for an unrelated text they would be wrong, so they go at once.
     */
    fun continues(old: String, now: String): Boolean {
        val o = old.trim().lowercase()
        val n = now.trim().lowercase()
        return o.isNotEmpty() && n.isNotEmpty() && (o.startsWith(n) || n.startsWith(o))
    }

    /**
     * The rows of each of [providers] to show for [query]: its answer for exactly [query], else
     * (while the next answer is on its way) its previous answer if that was for a [continues] text,
     * so typing never makes rows vanish for a moment and pop back. An answer for an unrelated text is
     * never shown. The new answer replaces the old one in one step; providers without rows are left out.
     */
    fun visible(providers: List<SearchProvider>, tagged: Map<String, TaggedItems>, query: String): List<ProvidedItems> =
        providers.mapNotNull { provider ->
            val found = tagged[provider.id]?.takeIf { it.query == query || continues(it.query, query) }?.items.orEmpty()
            if (found.isEmpty()) null else ProvidedItems(provider, found)
        }

    /** Asks [provider] about [query] off the main thread; a provider that throws just has no rows. */
    suspend fun ask(provider: SearchProvider, query: String, ctx: SearchContext): List<SearchItem> {
        if (provider.debounceMillis > 0) delay(provider.debounceMillis)
        return try {
            withContext(Dispatchers.Default) { provider.results(query, ctx) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * The rows of every provider in [providers] for [query], each shown as soon as it arrives. A new
 * keystroke (or new [ctx]) cancels what is still running; see [ProviderResults.visible] for what
 * stays on screen meanwhile.
 */
@Composable
internal fun rememberProviderResults(providers: List<SearchProvider>, query: String, ctx: SearchContext): List<ProvidedItems> {
    val tagged = remember { mutableStateMapOf<String, TaggedItems>() }
    val trimmed = query.trim()
    LaunchedEffect(providers, trimmed, ctx) {
        if (trimmed.isEmpty()) return@LaunchedEffect
        coroutineScope {
            providers.forEach { provider ->
                launch { tagged[provider.id] = TaggedItems(trimmed, ProviderResults.ask(provider, trimmed, ctx)) }
            }
        }
    }
    return ProviderResults.visible(providers, tagged, trimmed)
}
