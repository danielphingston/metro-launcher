package io.github.danielphingston.metro.live.api

import java.net.URL

/**
 * The expanded view's address can take values from the tile's answer: "{species.url}" follows a
 * link in it, "pokeapi.co/api/v2/pokemon-species/{id}" builds one. It is worked out again from the
 * tile's latest answer each time the view opens or the tile updates, so a changed link is followed.
 */
object ApiAddress {
    /** What an address worked out to: a [Url] to fetch, [Waiting] for the tile's answer, or a [Problem] to show. */
    sealed interface Resolved {
        data class Url(val url: String) : Resolved
        data object Waiting : Resolved
        data class Problem(val message: String) : Resolved
    }

    private val field = Regex("\\{[^{}]*\\}")

    /** Whether [text] takes values from the tile's answer. */
    fun usesFields(text: String): Boolean = '{' in text

    /**
     * [input] as saved: a plain https address ([ApiStore.address]), or one with {fields} whose
     * formulas read and whose fixed parts make an https address (a field alone may be the whole
     * address). Null for anything else.
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (!usesFields(text)) return ApiStore.address(text)
        if (text.length > ApiStore.MAX_URL || BuiltInFormulas.problem(text) != null) return null
        // Outside the braces the address follows the usual rules: fields stand in as a word.
        if (ApiStore.address(text.replace(field, "x")) == null) return null
        return when {
            text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring("http://".length)
            text.startsWith("{") -> text
            text.contains("://") -> null
            else -> "https://$text"
        }
    }

    private val fixedSite = Regex("^https://[^/{}?#]+/", RegexOption.IGNORE_CASE)

    /**
     * The tile's own address as saved: a plain https address, or one with formulas after the site
     * ("pokeapi.co/api/v2/pokemon/{random(1, 1025)}"). The tile has no answer yet when it asks, so
     * its formulas can use functions and the clock but no fields, and the site itself stays fixed:
     * its headers (an API key) only ever go to that one site. Null for anything else.
     */
    fun tileAddress(input: String): String? {
        val text = input.trim()
        if (!usesFields(text)) return ApiStore.address(text)
        val normal = normalize(text) ?: return null
        if (!fixedSite.containsMatchIn(normal)) return null
        val missing = BuiltInFormulas.missingPaths(normal, null, FormulaContext(System.currentTimeMillis())) ?: return null
        return normal.takeIf { missing.isEmpty() }
    }

    /** Why [input] can't be the tile's address, for the editor and the import. */
    fun tileProblem(input: String): String? {
        val text = input.trim()
        if (tileAddress(text) != null) return null
        if (!usesFields(text)) return "the tile's address must be an https address"
        BuiltInFormulas.problem(text)?.let { return "the tile's address: $it" }
        val normal = normalize(text) ?: return "the tile's address must make an https address"
        if (!fixedSite.containsMatchIn(normal)) return "the tile's address must start with its site, formulas go after it: api.example.com/items/{random(1, 100)}"
        return "the tile's address can use functions like random() and dayOfYear(), not fields: it has no answer yet"
    }

    /** The address to fetch for the tile's [template] at [now]: its formulas worked out afresh, a new random() each time. */
    fun fetchAddress(template: String, now: Long): String? = try {
        if (!usesFields(template)) {
            ApiStore.address(template)
        } else {
            val e = BuiltInFormulas.evaluate(template, null, FormulaContext(now))
            val url = e.takeIf { it.error == null }?.text?.trim()?.let { ApiStore.address(it) }
            url?.takeIf { host(it) == host(template.replace(field, "x")) }
        }
    } catch (e: Throwable) {
        null
    }

    /** [template] worked out against the tile's answer [root]; never throws. */
    fun resolve(template: String, root: Any?, hasData: Boolean, context: FormulaContext): Resolved = try {
        if (!usesFields(template)) {
            ApiStore.address(template)?.let { Resolved.Url(it) } ?: Resolved.Problem("the expanded view's address isn't an https address")
        } else if (!hasData) {
            Resolved.Waiting
        } else {
            val e = BuiltInFormulas.evaluate(template, root, context)
            val text = e.text.trim()
            when {
                e.error != null -> Resolved.Problem("the expanded view's address: ${e.error}")
                text.isEmpty() || text.endsWith("://") -> Resolved.Problem("the tile's answer has no value for the expanded view's address")
                else -> ApiStore.address(text)?.let { Resolved.Url(it) }
                    ?: Resolved.Problem("\"${text.take(60)}\" from the tile's answer isn't an https address")
            }
        }
    } catch (e: Throwable) {
        Resolved.Problem("can't work out the expanded view's address")
    }

    /**
     * The headers to send to [url]: a link from the answer may lead anywhere, so the tile's
     * headers (an API key) go only to the tile's own host. An address typed in full keeps them.
     */
    fun headersFor(template: String, url: String, tileUrl: String, headers: Map<String, String>): Map<String, String> =
        if (!usesFields(template) || host(url) == host(tileUrl.replace(field, "x"))) headers else emptyMap()

    private fun host(url: String): String? = runCatching { URL(url).host?.lowercase() }.getOrNull()
}
