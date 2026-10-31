package io.github.danielphingston.metro.data

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.widget.Toast
import io.github.danielphingston.metro.ui.search.WebSearch
import org.json.JSONObject

/**
 * Something an action tile opens. Each kind knows how to open itself and how it is saved, so a
 * tile pinned from the search results needs nothing else to work after a restart.
 */
sealed interface LaunchTarget {
    /** Starts it from [bounds] with [options]; false when nothing on the phone could open it. */
    fun open(context: Context, bounds: Rect?, options: Bundle?): Boolean

    fun toJson(): JSONObject

    /**
     * An activity intent, saved as its intent: URI. Only the URI's contents count (extras that
     * aren't plain values are not kept), so two targets are equal when their URIs are.
     */
    class Activity(val intent: Intent) : LaunchTarget {
        val uri: String by lazy { intent.toUri(Intent.URI_INTENT_SCHEME) }

        override fun open(context: Context, bounds: Rect?, options: Bundle?): Boolean {
            val start = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { sourceBounds = bounds }
            val started = runCatching { context.startActivity(start, options) }.isSuccess
            // The app it pointed into was uninstalled, or changed what it accepts.
            if (!started) Toast.makeText(context, "this isn't available any more", Toast.LENGTH_SHORT).show()
            return started
        }

        override fun toJson(): JSONObject = JSONObject().put("kind", "activity").put("uri", uri)

        override fun equals(other: Any?) = other is Activity && other.uri == uri
        override fun hashCode() = uri.hashCode()
        override fun toString() = "Activity($uri)"
    }

    /**
     * A web page: [url] in the default browser when [inBrowser] (a web search, even when another
     * app claims the domain), else with normal link handling (a typed address).
     */
    data class Web(val url: String, val inBrowser: Boolean = true) : LaunchTarget {
        override fun open(context: Context, bounds: Rect?, options: Bundle?) =
            WebSearch.open(context, url, bounds, options, inBrowser)

        override fun toJson(): JSONObject = JSONObject().put("kind", "web").put("url", url).put("inBrowser", inBrowser)
    }

    companion object {
        /** The target saved as [obj], or null for a kind this version doesn't know or a damaged entry. */
        fun fromJson(obj: JSONObject): LaunchTarget? = when (obj.optString("kind")) {
            "activity" -> runCatching { Activity(Intent.parseUri(obj.getString("uri"), Intent.URI_INTENT_SCHEME)) }.getOrNull()
            "web" -> Web(obj.getString("url"), obj.optBoolean("inBrowser", true))
            else -> null
        }
    }
}

/**
 * The picture on an action tile, saved as a reference: a search glyph by its id, an installed
 * app's icon, or a contact's photo by the contact's URI.
 */
sealed interface ActionIcon {
    data class Glyph(val id: String) : ActionIcon

    data class App(val app: AppKey) : ActionIcon

    /** Read from the contacts each time it is drawn, so a new photo shows up; the person drawing without one. */
    data class Contact(val uri: String) : ActionIcon
}
