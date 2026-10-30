package io.github.danielphingston.metro.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SmartFeature
import java.util.Locale

/**
 * One smart search feature. A provider turns the typed text into rows; the overlay knows nothing
 * else about it. To add one see the note at the top of [SearchProviders].
 */
interface SearchProvider {
    /** Stable and unique; tags the provider's results and keys its rows. */
    val id: String

    /** The Metro Plus switch that turns this provider on, or null for a provider that is always on. */
    val feature: SmartFeature?

    /** Where its rows go relative to the built-in ones; see [Placement]. */
    val placement: Placement get() = Placement.MIDDLE

    /** A lowercase caption shown above its rows ("settings"), or null for none. */
    val section: String? get() = null

    /** How long to wait after the last keystroke before asking, for providers that do real work per query. */
    val debounceMillis: Long get() = 0L

    /**
     * The rows for [query] (already trimmed, never blank). Runs off the main thread and is
     * cancelled when the next keystroke arrives, so it may suspend freely but must not touch UI.
     * Return an empty list when nothing matches.
     */
    suspend fun results(query: String, ctx: SearchContext): List<SearchItem>
}

/**
 * Where a provider's rows sit. The overlay shows [TOP], then the typed address and the apps and
 * shortcuts, then [MIDDLE], then the web row, then [BOTTOM]. Within a place the registry order wins.
 */
enum class Placement { TOP, MIDDLE, BOTTOM }

/** What a provider may look at: the apps, the settings and a clock. [now] is read when asked, not when built. */
class SearchContext(
    val context: Context,
    val apps: List<AppInfo>,
    val settings: LauncherSettings,
    val locale: Locale = Locale.getDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val now: Long get() = clock()
}

/** A row a provider produced. [key] only has to be unique within its provider. */
data class SearchItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val icon: SearchIcon,
    /** What tapping the row does. */
    val action: ItemAction,
    /** Small round buttons at the end of the row, e.g. call and message on a contact. */
    val buttons: List<ItemButton> = emptyList(),
    /** Larger title, for an answer such as the calculator's. */
    val prominent: Boolean = false,
    /**
     * Set when the row is an app shortcut (see [WhatsAppMatcher.shortcutKey]): it is left out
     * when the shortcuts section already lists that same shortcut.
     */
    val shortcutKey: String? = null,
    /**
     * Whether a top row may take Enter (see [topResult]). False for a row that only displays or
     * copies something, such as the calculator's answer: "555-1234" is also a sum, but Enter there
     * should still reach the app or web search.
     */
    val capturesEnter: Boolean = true,
)

/** The picture at the start of a row (or on a button). */
sealed interface SearchIcon {
    /** An installed app's icon, drawn like in the app list. */
    data class App(val app: AppInfo) : SearchIcon

    /** A hairline drawing in the foreground colour; two glyphs with the same [id] are the same glyph. */
    class Glyph(val id: String, val draw: DrawScope.(Color) -> Unit) : SearchIcon {
        override fun equals(other: Any?) = other is Glyph && other.id == id
        override fun hashCode() = id.hashCode()
    }

    /** A picture such as a contact photo, cropped to the icon square. */
    data class Image(val bitmap: ImageBitmap) : SearchIcon
}

/** A small round button on a row; [label] is its accessibility description. */
data class ItemButton(val label: String, val icon: SearchIcon, val action: ItemAction)

/** What happens when a row or one of its buttons is tapped. The overlay closes first. */
sealed interface ItemAction {
    /** Starts [intent] with the launcher's usual transition from the tapped row. */
    data class Start(val intent: Intent) : ItemAction

    /** Like [Start] for anything that is not one plain intent (a shortcut, a chooser...); returns whether it opened. */
    class Open(val run: (ActionEnv) -> Boolean) : ItemAction

    /** Does something in place, with no screen change (copy to the clipboard, edit mode...). */
    class Run(val run: (ActionEnv) -> Unit) : ItemAction
}

/** What an [ItemAction] gets to work with: screen-space bounds and options of the launch, and the launcher itself. */
class ActionEnv(
    val context: Context,
    val bounds: Rect?,
    val options: Bundle?,
    val commands: LauncherCommands,
)

/** The parts of the launcher a search row can ask for. Implemented by the launcher screen. */
interface LauncherCommands {
    /** Shows the start screen's edit mode. */
    fun editStart()

    fun appInfo(app: AppInfo)

    fun uninstall(app: AppInfo)

    /** Remembers [question] as the last one asked, for the assistant tile. */
    fun recordAssistantQuestion(question: String)
}
