package io.github.danielphingston.metro.ui.search

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The WhatsApp apps and how to tell their pieces from other apps'. */
internal object WhatsAppApps {
    const val PERSONAL = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    val packages = listOf(PERSONAL, BUSINESS)

    /** The data-row types WhatsApp adds to a contact; opening one with VIEW starts that chat. */
    val profileMimeTypes = listOf(
        "vnd.android.cursor.item/vnd.com.whatsapp.profile",
        "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile",
    )

    fun isWhatsApp(packageName: String) = packageName in packages

    /**
     * WhatsApp's own icon, read from the installed app (never bundled): the app that owns
     * [preferred] when installed, else the other WhatsApp, else a neutral chat bubble.
     */
    fun icon(apps: List<AppInfo>, preferred: String = PERSONAL): SearchIcon {
        val installed = (listOf(preferred) + packages).distinct().firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { it.key.packageName == pkg && it.key.userSerial == 0L } ?: apps.firstOrNull { it.key.packageName == pkg }
        }
        return installed?.let { SearchIcon.App(it) } ?: SearchGlyphs.chat
    }

    /** Whether [mimeType] is a WhatsApp contact data row. */
    fun isProfileMime(mimeType: String?) = mimeType != null && mimeType in profileMimeTypes

    /** The app that owns a contact data row of [mimeType]: Business for its own type, else WhatsApp. */
    fun packageForMime(mimeType: String): String = if ("w4b" in mimeType) BUSINESS else PERSONAL

    /** The category Android gives conversation shortcuts. */
    const val CONVERSATION_CATEGORY = "android.shortcut.conversation"

    /**
     * Whether a shortcut is a chat or group rather than one of the app's own actions: apps put
     * conversations in the conversation category, and Android only caches long-lived conversations.
     */
    fun isConversation(cached: Boolean, categories: Set<String>?): Boolean =
        cached || categories?.contains(CONVERSATION_CATEGORY) == true
}

/** A WhatsApp chat or group the system has a shortcut for. */
internal class ChatHit(
    val packageName: String,
    val id: String,
    val userSerial: Long,
    val label: String,
    val longLabel: String?,
)

/**
 * WhatsApp's conversation shortcuts, read when a search needs them and kept for [ttlMillis]. Beyond
 * what the shortcut section already finds (dynamic and pinned shortcuts) this asks for the
 * *cached* ones, which Android keeps for conversations that left WhatsApp's own list, so older chats
 * match too. Empty unless Metro is the default home app.
 */
internal class WhatsAppIndex(
    private val context: Context,
    private val ttlMillis: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var all: List<ChatHit>? = null

    @Volatile
    private var loadedAt = 0L

    suspend fun search(query: String, limit: Int): List<ChatHit> = withContext(Dispatchers.Default) {
        val hits = all?.takeIf { clock() - loadedAt < ttlMillis } ?: load().let { loaded ->
            // A partial read (a profile that was locked) or "not the default home app" is used but not kept.
            if (loaded.complete) {
                all = loaded.hits
                loadedAt = clock()
            }
            loaded.hits
        }
        WhatsAppMatcher.top(hits, query, limit)
    }

    /** Forgets the loaded chats, e.g. when an app was installed, updated or removed. */
    fun invalidate() {
        all = null
    }

    private fun load(): ShortcutLoad<ChatHit> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
            LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
        val hits = ArrayList<ChatHit>()
        var complete = true
        try {
            if (!launcherApps.hasShortcutHostPermission()) return ShortcutLoad(emptyList(), false)
            for (user in launcherApps.profiles) {
                if (!shortcutsReadable(context, user)) {
                    complete = false
                    continue
                }
                val serial = ShortcutTiles.serialOf(context, user)
                for (pkg in WhatsAppApps.packages) {
                    val query = LauncherApps.ShortcutQuery().setQueryFlags(flags).setPackage(pkg)
                    try {
                        launcherApps.getShortcuts(query, user).orEmpty().mapNotNullTo(hits) { info -> hit(info, serial) }
                    } catch (_: SecurityException) {
                        complete = false
                    } catch (_: IllegalStateException) {
                        complete = false
                    }
                }
            }
        } catch (_: SecurityException) {
            return ShortcutLoad(emptyList(), false)
        } catch (_: IllegalStateException) {
            return ShortcutLoad(emptyList(), false)
        }
        return ShortcutLoad(hits.distinctBy { Triple(it.packageName, it.id, it.userSerial) }, complete)
    }

    private fun hit(info: ShortcutInfo, serial: Long): ChatHit? {
        val label = info.shortLabel?.toString()?.trim().orEmpty()
        if (!info.isEnabled || label.isEmpty() || !WhatsAppApps.isConversation(info.isCached, info.categories)) return null
        return ChatHit(info.`package`, info.id, serial, label, info.longLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** The last picture [icon] read for each chat, so a row can show it at once while it is read again. */
    private val lastIcons = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun iconKey(hit: ChatHit, sizePx: Int) = "${WhatsAppMatcher.shortcutKey(hit.packageName, hit.id, hit.userSerial)}@$sizePx"

    /** What [icon] last returned for [hit], without asking the system; null if never read or dropped. */
    fun lastIcon(hit: ChatHit, sizePx: Int): Bitmap? = lastIcons.get(iconKey(hit, sizePx))

    /** The shortcut's own picture (the contact or group photo), or null. Blocking; always read fresh. */
    fun icon(hit: ChatHit, sizePx: Int): Bitmap? = readIcon(hit, sizePx).also { bitmap ->
        if (bitmap != null) lastIcons.put(iconKey(hit, sizePx), bitmap) else lastIcons.remove(iconKey(hit, sizePx))
    }

    private fun readIcon(hit: ChatHit, sizePx: Int): Bitmap? = try {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = ShortcutTiles.userFor(context, hit.userSerial) ?: return null
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED,
            )
            .setPackage(hit.packageName)
            .setShortcutIds(listOf(hit.id))
        val info = launcherApps.getShortcuts(query, user)?.firstOrNull()
        val drawable = info?.let { launcherApps.getShortcutIconDrawable(it, 0) }
        drawable?.let {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
                it.setBounds(0, 0, sizePx, sizePx)
                it.draw(Canvas(bitmap))
            }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** The size chat pictures are read at, shared by the chat rows and the shortcut rows. */
        const val ICON_PX = 96

        @Volatile
        private var instance: WhatsAppIndex? = null

        fun shared(context: Context): WhatsAppIndex =
            instance ?: synchronized(this) { instance ?: WhatsAppIndex(context.applicationContext ?: context).also { instance = it } }
    }
}

/** Pure matching of chats by label. */
internal object WhatsAppMatcher {
    fun top(chats: List<ChatHit>, query: String, limit: Int): List<ChatHit> =
        SearchRanking.top(chats, query, limit) { listOfNotNull(it.label, it.longLabel) }.map { it.item }

    /** The key a shortcut row uses, so the same shortcut isn't listed twice; see [SearchItem.shortcutKey]. */
    fun shortcutKey(packageName: String, id: String, userSerial: Long) = "$packageName/$id#$userSerial"
}

/** Feature 9: WhatsApp chats and groups by name. */
internal class WhatsAppProvider(
    private val chats: (Context) -> suspend (String, Int) -> List<ChatHit> = { context ->
        { query, limit -> WhatsAppIndex.shared(context).search(query, limit) }
    },
    private val iconOf: (Context, ChatHit) -> Bitmap? = { context, hit -> WhatsAppIndex.shared(context).icon(hit, WhatsAppIndex.ICON_PX) },
) : SearchProvider {
    override val id = "whatsapp"
    override val feature = SmartFeature.WHATSAPP
    override val section = "whatsapp"

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val found = chats(ctx.context)(query, MAX_ROWS)
        return found.map { hit ->
            SearchItem(
                key = "${hit.packageName}/${hit.id}#${hit.userSerial}",
                title = hit.label,
                subtitle = hit.longLabel?.takeIf { it != hit.label } ?: if (hit.packageName == WhatsAppApps.BUSINESS) "WhatsApp Business" else "WhatsApp",
                icon = iconOf(ctx.context, hit)?.let { SearchIcon.Image(it.asImageBitmap()) }
                    ?: WhatsAppApps.icon(ctx.apps, hit.packageName),
                action = ItemAction.Open { env ->
                    ShortcutTiles.launch(
                        env.context,
                        TileContent.Shortcut(hit.packageName, hit.id, hit.userSerial, hit.label),
                        env.bounds,
                        env.options,
                    )
                },
                shortcutKey = WhatsAppMatcher.shortcutKey(hit.packageName, hit.id, hit.userSerial),
                pin = TileContent.Shortcut(hit.packageName, hit.id, hit.userSerial, hit.label),
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 4
    }
}
