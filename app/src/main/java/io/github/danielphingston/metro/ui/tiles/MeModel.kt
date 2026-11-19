package io.github.danielphingston.metro.ui.tiles

import io.github.danielphingston.metro.data.TileSize
import android.app.PendingIntent
import io.github.danielphingston.metro.live.MeApp
import io.github.danielphingston.metro.live.MeSnapshot

/** One notification (or, with notification text off, one app) the me tile flips to. [key] is the system key a tap opens. */
data class MeFace(val key: String, val appKey: String, val appName: String?, val title: String?, val text: String?)

/** What the me tile draws, decided from the feed, the access state and the "show message text on tiles" setting. */
data class MeModel(val state: State, val count: Int, val icons: List<String>, val faces: List<MeFace>) {
    enum class State { NO_ACCESS, EMPTY, ACTIVE }

    /** The newest face; the front shows it as the "latest" line. */
    val latest: MeFace? get() = faces.firstOrNull()

    /** Small tiles never flip. */
    fun hasBack(size: TileSize): Boolean = size != TileSize.SMALL && state == State.ACTIVE && faces.isNotEmpty()

    fun description(): String = when (state) {
        State.NO_ACCESS -> "me, turn on notification access"
        State.EMPTY -> "me, nothing new"
        State.ACTIVE -> buildString {
            append("me, ").append(count).append(if (count == 1) " notification" else " notifications")
            latest?.let { f ->
                if (f.appName != null) {
                    append(", latest from ").append(f.appName)
                    f.title?.let { append(": ").append(it) }
                } else {
                    f.title?.let { append(", latest: ").append(it) }
                }
            }
        }
    }

    companion object {
        const val MAX_FACES = 5

        /**
         * The label to show for [packageName], or null when [label] is missing or just the package name
         * (or looks like one: two or more dots and no spaces), so a name like "com.android.systemui"
         * never reaches the tile or TalkBack. Real names like "mobile.de" or "wetter.com" stay.
         */
        fun humanLabel(label: CharSequence?, packageName: String): String? {
            val text = label?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.equals(packageName, ignoreCase = true)) return null
            val packageLike = text.none { it.isWhitespace() } && text.count { it == '.' } >= 2
            return if (packageLike) null else text
        }

        /** How many app icons the front shows at [size]. */
        fun iconSlots(size: TileSize): Int = when (size) {
            TileSize.SMALL -> 0
            TileSize.MEDIUM -> 3
            TileSize.WIDE -> 6
            TileSize.LARGE -> 8
        }

        /**
         * [nameOf] gives an app's human label, or null when there is none (a package name is never shown). With [showContent] off the faces are apps and counts only;
         * a notification whose text is withheld (SECRET) shows its app name and nothing else.
         */
        fun of(
            snapshot: MeSnapshot,
            hasAccess: Boolean,
            showContent: Boolean,
            size: TileSize,
            nameOf: (MeApp) -> String?,
        ): MeModel {
            if (!hasAccess) return MeModel(State.NO_ACCESS, 0, emptyList(), emptyList())
            if (snapshot.total <= 0) return MeModel(State.EMPTY, 0, emptyList(), emptyList())
            val names = snapshot.apps.associate { it.appKey to nameOf(it) }
            val faces = if (showContent) {
                snapshot.recent.take(MAX_FACES).map {
                    MeFace(
                        it.key, it.appKey, names[it.appKey],
                        it.preview?.title?.takeIf(String::isNotBlank), it.preview?.text?.takeIf(String::isNotBlank),
                    )
                }
            } else {
                snapshot.apps.take(MAX_FACES).map {
                    MeFace(it.newestKey, it.appKey, names[it.appKey], null, "${it.count} new")
                }
            }
            return MeModel(State.ACTIVE, snapshot.total, snapshot.apps.take(iconSlots(size)).map { it.appKey }, faces)
        }
    }
}

/** What a tap on the me tile opens: the notification's own intent when there is one, else its app. */
data class MeTapTarget(val packageName: String, val intent: PendingIntent?)

internal object MeTap {
    /**
     * [shownKey] is the notification on the face now (null on the front summary). A key that is gone
     * falls back to the newest notification overall, so a stale face never opens nothing.
     */
    fun resolve(snapshot: MeSnapshot, shownKey: String?): MeTapTarget? {
        if (shownKey != null) {
            snapshot.recent.firstOrNull { it.key == shownKey }?.let { return MeTapTarget(it.packageName, it.intent) }
            snapshot.apps.firstOrNull { it.newestKey == shownKey }?.let { return MeTapTarget(it.packageName, it.newestIntent) }
        }
        val newest = snapshot.apps.firstOrNull() ?: return null
        // The summary with a single app opens that app; with several it opens the newest notification.
        if (shownKey == null && snapshot.apps.size == 1) return MeTapTarget(newest.packageName, null)
        return MeTapTarget(newest.packageName, newest.newestIntent)
    }
}
