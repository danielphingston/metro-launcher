package io.github.danielphingston.metro.ui.tiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A tile setup shared in from another app, waiting for the launcher to open the API tile editor on it. */
internal object TileImports {
    private val waiting = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = waiting

    /** Keeps [text] for the editor; false when there is nothing to import. */
    fun offer(text: String?): Boolean {
        val kept = text?.trim()?.take(ApiImport.MAX_CHARS)?.takeIf { it.isNotEmpty() } ?: return false
        waiting.value = kept
        return true
    }

    fun clear() {
        waiting.value = null
    }
}
