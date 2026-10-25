package io.github.danielphingston.metro.data

import java.net.URLEncoder

/**
 * The web search behind the search overlay's "web" row. It opens in the default browser; Metro
 * itself never talks to these sites.
 */
enum class SearchEngine(val label: String, private val prefix: String) {
    GOOGLE("google", "https://www.google.com/search?q="),
    BING("bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("duckduckgo", "https://duckduckgo.com/?q="),
    ;

    /** The results page for [query], percent-encoded (spaces as "+"). */
    fun searchUrl(query: String): String = prefix + URLEncoder.encode(query.trim(), "UTF-8")
}
