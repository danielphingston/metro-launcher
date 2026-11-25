package io.github.danielphingston.metro.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.delay

/**
 * One searchable setting: what it is called ([title]), the page it is on, other words people
 * may type for it, and the [anchor] that marks it on its page (see [settingAnchor]); null for a
 * whole page. [shownWhen] is false while the page doesn't show it (e.g. a photo option without a
 * photo background); such a result still opens its page.
 */
internal class SettingEntry(
    val anchor: String?,
    val title: String,
    val page: SettingsPage,
    val keywords: List<String> = emptyList(),
    val alwaysShown: Boolean = true,
    val shownWhen: (LauncherSettings) -> Boolean = { true },
)

/**
 * Every setting the settings search finds, in page order. Each [SettingEntry.anchor] must be on
 * its page and every anchor on a page must be here: SettingsSearchTest renders the pages to check
 * both. Pure.
 */
internal object SettingsIndex {
    private fun e(anchor: String?, title: String, page: SettingsPage, vararg keywords: String, alwaysShown: Boolean = true) =
        SettingEntry(anchor, title, page, keywords.toList(), alwaysShown)

    val entries: List<SettingEntry> = listOf(
        // start
        e("layouts", "layouts", SettingsPage.START, "work", "weekend", "several start screens", "switch", "same grid", "profiles"),
        e("tiles_per_row", "tiles per row", SettingsPage.START, "columns", "grid", "show more tiles", "tile size", "bigger", "smaller"),
        e("start_padding", "start screen padding", SettingsPage.START, "margin", "spacing", "gap", "top", "bottom", "left", "right", "edges"),
        e("accent_arrows", "accent colour arrows", SettingsPage.START, "buttons", "arrows", "color"),
        e("start_background", "start background", SettingsPage.START, "wallpaper", "photo", "picture", "image", "solid", "black"),
        e("tile_transparency", "tile transparency", SettingsPage.START, "opacity", "see-through", "translucent"),
        SettingEntry(
            "photo_moves", "photo moves with the tiles", SettingsPage.START, listOf("parallax", "slide", "wallpaper"),
            alwaysShown = false, shownWhen = { it.background == StartBackground.PHOTO_TILES },
        ),
        e("tile_colour", "tile colour", SettingsPage.START, "color", "icon colour", "vividness", "monochrome", "colourful"),
        e(null, "manage layouts", SettingsPage.LAYOUTS, "save layout", "rename", "duplicate", "delete layout", "new layout"),
        // app list
        e("plain_icons", "plain icons in app list", SettingsPage.APP_LIST, "icon background", "square", "app drawer"),
        e("scrubber_highlight", "scrubber highlight", SettingsPage.APP_LIST, "alphabet", "letter", "circle"),
        e("highlight_position", "highlight position", SettingsPage.APP_LIST, "alphabet", "letter"),
        e("scrubber_position", "scrubber position", SettingsPage.APP_LIST, "alphabet", "height"),
        // search
        e("search_engine", "search engine", SettingsPage.SEARCH, "google", "web", "duckduckgo", "bing"),
        e("pull_distance", "pull distance", SettingsPage.SEARCH, "pull down", "swipe down", "search gesture"),
        e("smart_search", "smart search", SettingsPage.SEARCH, "assistant", "calculator", "contacts", "history", "recent searches"),
        e(null, "search in apps", SettingsPage.SEARCH_IN_APPS, "search apps", "order"),
        e(null, "search keywords", SettingsPage.SEARCH_KEYWORDS, "shortcuts", "youtube", "amazon", "bang"),
        e(null, "what can I type?", SettingsPage.SEARCH_HELP, "help", "phrases", "commands"),
        // appearance
        e("theme", "theme", SettingsPage.APPEARANCE, "dark", "light", "mode", "night"),
        e("accent_colour", "accent colour", SettingsPage.APPEARANCE, "color", "highlight colour"),
        SettingEntry(
            "accent_from_background", "accent colour from my background", SettingsPage.APPEARANCE, listOf("photo", "wallpaper", "color"),
            alwaysShown = false, shownWhen = { it.showsPhoto && it.backgroundPhoto != null },
        ),
        e("themed_icons", "themed app icons", SettingsPage.APPEARANCE, "monochrome", "white icons", "material you"),
        e("icon_pack", "icon pack", SettingsPage.APPEARANCE, "icons", "custom icons"),
        // animations
        e("launch_animation", "launch animation", SettingsPage.ANIMATIONS, "open app", "turnstile", "fade", "slide"),
        e("animation_speed", "animation speed", SettingsPage.ANIMATIONS, "faster", "slower", "speed"),
        e("tilt", "tilt when pressed", SettingsPage.ANIMATIONS, "press", "3d", "tilt strength"),
        e("live_tile_flips", "live tile flips", SettingsPage.ANIMATIONS, "flip", "interval", "animate tiles"),
        e("swing_pages", "swing pages", SettingsPage.ANIMATIONS, "pages", "app list animation"),
        // tiles
        e("live_tiles", "live tiles", SettingsPage.TILES, "notifications", "notification access", "badges", "counts"),
        e("message_text", "show message text on tiles", SettingsPage.TILES, "preview", "privacy", "notification text"),
        e("weather", "weather", SettingsPage.TILES, "location", "city", "temperature", "fahrenheit", "celsius"),
        e("photos", "photos", SettingsPage.TILES, "gallery", "pictures", "slideshow"),
        e("people", "people", SettingsPage.TILES, "contacts", "faces"),
        // metro plus
        e(null, "metro plus", SettingsPage.METRO_PLUS, "buy", "unlock", "purchase", "pro", "premium"),
        e("assistant", "assistant", SettingsPage.METRO_PLUS, "ai", "chatgpt", "gemini", "claude", "ask"),
        e("feed_tiles", "feed tiles", SettingsPage.METRO_PLUS, "rss", "lemmy", "news"),
        e(null, "gestures", SettingsPage.GESTURES, "double tap", "two finger", "swipe", "pinch", "sleep", "lock screen"),
        // backup
        e("backup_export", "export backup", SettingsPage.BACKUP, "save", "google drive", "file", "copy settings"),
        e("backup_restore", "restore from backup", SettingsPage.BACKUP, "import", "new phone", "move"),
        // about
        SettingEntry("default_home", "default home app", SettingsPage.ABOUT, listOf("launcher", "home button"), alwaysShown = false),
        e("battery", "battery", SettingsPage.ABOUT, "sleep", "unrestricted", "background"),
        e("run_setup", "run setup again", SettingsPage.ABOUT, "first run", "tutorial", "onboarding", "welcome"),
        SettingEntry("crash_log", "last crash", SettingsPage.ABOUT, listOf("crash", "bug", "report"), alwaysShown = false),
        e("about", "about", SettingsPage.ABOUT, "version"),
    )

    /** The anchors the index knows. */
    val anchors: Set<String> = entries.mapNotNullTo(LinkedHashSet()) { it.anchor }

    /**
     * The entries matching [query], best first: every word typed must start a word of the
     * title, a keyword or the page's title. A title that starts with the query ranks first, then
     * title matches, then keyword or page matches; ties keep page order. Empty for a blank query.
     */
    fun search(query: String, limit: Int = 20): List<SettingEntry> {
        val words = wordsOf(query)
        if (words.isEmpty()) return emptyList()
        val normalized = words.joinToString(" ")
        return entries.mapIndexedNotNull { index, entry ->
            val titleWords = wordsOf(entry.title)
            val otherWords = entry.keywords.flatMap(::wordsOf) + wordsOf(entry.page.title)
            if (!words.all { w -> titleWords.any { it.startsWith(w) } || otherWords.any { it.startsWith(w) } }) return@mapIndexedNotNull null
            val rank = when {
                titleWords.joinToString(" ").startsWith(normalized) -> 0
                words.all { w -> titleWords.any { it.startsWith(w) } } -> 1
                else -> 2
            }
            Triple(rank, index, entry)
        }.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
    }

    private fun wordsOf(text: String): List<String> =
        text.lowercase().replace("color", "colour").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}

/** Which setting the settings screen was asked to show; [settingAnchor] scrolls to it and clears it. */
@Stable
internal class SettingTarget {
    var anchor by mutableStateOf<String?>(null)
}

internal val LocalSettingTarget = staticCompositionLocalOf<SettingTarget?> { null }

/** How long the highlight of a found setting holds before it fades. */
private const val HIGHLIGHT_HOLD_MILLIS = 700L

/**
 * Marks where the setting [key] (a [SettingsIndex] anchor) is on its page. When settings search
 * opens the page for it, it is scrolled into view, with some room below for what follows it, and
 * flashes in the accent colour once. Any other time it only compares [key] with the target: no
 * effect runs and nothing is drawn.
 */
internal fun Modifier.settingAnchor(key: String): Modifier = composed {
    val target = LocalSettingTarget.current
    val aimed = target != null && target.anchor == key
    val tagged = testTag("setting:$key")
    if (!aimed) return@composed tagged
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val room = with(LocalDensity.current) { 160.dp.toPx() }
    val inset = with(LocalDensity.current) { 6.dp.toPx() }
    val accent = Metro.colors.accent
    LaunchedEffect(target) {
        try {
            // Let the page be laid out first: it has just been opened.
            withFrameNanos { }
            withFrameNanos { }
            requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + room))
            glow.animateTo(0.28f, tween(180))
            delay(HIGHLIGHT_HOLD_MILLIS)
            glow.animateTo(0f, tween(520))
        } finally {
            if (target.anchor == key) target.anchor = null
        }
    }
    tagged
        .onSizeChanged { size = it }
        .bringIntoViewRequester(requester)
        .drawBehind {
            val alpha = glow.value
            if (alpha > 0f) {
                drawRect(accent.copy(alpha = alpha), Offset(-inset, -inset), Size(this.size.width + 2 * inset, this.size.height + 2 * inset))
            }
        }
}

/**
 * The settings matching [query], each with the page it is on; a tap opens it ([onFind]). Settings
 * their page doesn't show now (see [SettingEntry.shownWhen]) are left out, so a result never
 * opens a page without its setting.
 */
@Composable
internal fun SettingsSearchResults(query: String, settings: LauncherSettings, onFind: (SettingEntry) -> Unit) {
    val colors = Metro.colors
    val results = remember(query, settings) { SettingsIndex.search(query).filter { it.shownWhen(settings) } }
    if (results.isEmpty()) {
        MetroText("Nothing in settings matches “${query.trim()}”.", MetroType.caption, color = colors.subtle)
        return
    }
    results.forEach { entry ->
        Column(
            Modifier
                .fillMaxWidth()
                .metroTilt()
                .clickable(role = Role.Button) { onFind(entry) }
                .padding(vertical = 8.dp),
        ) {
            MetroText(entry.title, MetroType.body, maxLines = 2)
            MetroText(entry.page.path(), MetroType.caption, color = colors.subtle, maxLines = 1)
        }
    }
}

/** Where a page is, as people get there: "search › search keywords". */
private fun SettingsPage.path(): String = listOfNotNull(parent?.title, title).joinToString(" › ")
