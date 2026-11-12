package io.github.danielphingston.metro.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** The start screen gestures that can be given an action. */
enum class Gesture(val id: String, val title: String) {
    DOUBLE_TAP("double_tap", "double tap"),
    TWO_FINGER_DOWN("two_finger_down", "two-finger swipe down"),
    TWO_FINGER_UP("two_finger_up", "two-finger swipe up"),
    PINCH_IN("pinch_in", "pinch in"),
    ;

    /** DataStore key of the chosen [GestureAction] id. */
    val actionKey get() = stringPreferencesKey("gesture_$id")

    /** DataStore key of the app for [GestureAction.OPEN_APP], an [AppKey.serialize] string. */
    val appKey get() = stringPreferencesKey("gesture_${id}_app")
}

/** What a gesture can do. [id] is stored, so it never changes once shipped. */
enum class GestureAction(val id: String, val label: String, val access: Access = Access.NONE) {
    NONE("none", "none"),
    SLEEP("sleep", "turn the screen off", Access.REQUIRED),
    NOTIFICATIONS("notifications", "notifications", Access.FALLBACK),
    QUICK_SETTINGS("quick_settings", "quick settings", Access.FALLBACK),
    SEARCH("search", "search"),
    APP_LIST("app_list", "app list"),
    EDIT_TILES("edit_tiles", "edit tiles"),
    METRO_SETTINGS("metro_settings", "metro settings"),
    RECENTS("recents", "recent apps", Access.REQUIRED),
    OPEN_APP("open_app", "open an app"),
    ;

    /** Whether the action needs Metro's optional accessibility service. */
    enum class Access {
        NONE,

        /** Only the service can do it. */
        REQUIRED,

        /** Tried the direct way first; the service is the fallback. */
        FALLBACK,
    }

    /** Whether the settings should explain the accessibility service and offer to turn it on. */
    val usesAccessibility: Boolean get() = access != Access.NONE

    companion object {
        /** Unknown ids (a newer backup, a hand-edited file) mean no action. */
        fun fromId(id: String?): GestureAction = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** The action of one gesture; [app] is only used by [GestureAction.OPEN_APP]. */
data class GestureBinding(val action: GestureAction = GestureAction.NONE, val app: AppKey? = null) {
    /** Whether this gesture does anything at all (an app gesture without a usable app does not). */
    val active: Boolean get() = action != GestureAction.NONE && (action != GestureAction.OPEN_APP || app != null)
}

/** Every gesture's binding; a gesture never set does nothing. */
data class GestureSettings(val bindings: Map<Gesture, GestureBinding> = emptyMap()) {
    operator fun get(gesture: Gesture): GestureBinding = bindings[gesture] ?: GestureBinding()

    companion object {
        fun from(prefs: Preferences) = GestureSettings(
            Gesture.entries.associateWith { gesture ->
                GestureBinding(
                    GestureAction.fromId(prefs[gesture.actionKey]),
                    prefs[gesture.appKey]?.let(AppKey::parse),
                )
            },
        )
    }
}
