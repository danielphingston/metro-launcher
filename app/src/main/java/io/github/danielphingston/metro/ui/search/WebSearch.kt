package io.github.danielphingston.metro.ui.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** Turns what was typed into web addresses and opens them in the default browser. */
object WebSearch {
    private val scheme = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** A host (with at least one dot and a letters-only ending), then an optional port and path. */
    private val domain =
        Regex("^(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+\\p{L}{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?$")

    /** Whether [query] is an http(s) address or a bare domain such as "example.com/page". */
    fun looksLikeUrl(query: String): Boolean {
        val q = query.trim()
        return scheme.matches(q) || domain.matches(q)
    }

    /** [query] as a full address: "https://" is added to a bare domain. */
    fun toUrl(query: String): String {
        val q = query.trim()
        return if (scheme.matches(q)) q else "https://$q"
    }

    /** Hands [url] to the browser the user chose as default; false (with a toast) when there is none. */
    fun open(context: Context, url: String, bounds: Rect?, options: Bundle?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.sourceBounds = bounds
        return try {
            context.startActivity(intent, options)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "no browser found", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
