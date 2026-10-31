package io.github.danielphingston.metro.ui.settings

/** What turning a permission-backed toggle on does next. */
internal enum class PermissionStep {
    /** The permission is held: just save the choice. */
    SAVE,

    /** Ask the system for it. */
    REQUEST,

    /** The system no longer shows its dialog for this app, so only the app info page can grant it. */
    OPEN_APP_INFO,
}

internal object PermissionLogic {
    /**
     * Whether a request was refused for good: it was denied and the system would not explain why
     * the permission is needed (no rationale), which after a denial means "don't ask again".
     */
    fun permanentlyDenied(denied: Boolean, shouldShowRationale: Boolean): Boolean = denied && !shouldShowRationale

    fun step(granted: Boolean, permanentlyDenied: Boolean): PermissionStep = when {
        granted -> PermissionStep.SAVE
        permanentlyDenied -> PermissionStep.OPEN_APP_INFO
        else -> PermissionStep.REQUEST
    }

    /**
     * Whether a request's [results] satisfy the feature. A request may ask for several permissions
     * (a group such as coarse and fine location can be asked together), but only [required] counts.
     */
    fun satisfied(results: Map<String, Boolean>, required: String): Boolean = results[required] == true
}
