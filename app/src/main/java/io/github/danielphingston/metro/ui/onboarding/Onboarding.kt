package io.github.danielphingston.metro.ui.onboarding

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.metroDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The steps of the first-run setup, in order. */
internal enum class OnboardingStep {
    WELCOME, LIVE_TILES, LOOK, DONE;

    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)
    val previous: OnboardingStep? get() = entries.getOrNull(ordinal - 1)
    val isLast: Boolean get() = next == null
}

object Onboarding {
    /**
     * Whether the first-run setup shows. [layoutSaved] is whether a tile layout had been saved when
     * the launcher started, [done] the stored `onboarding_done` flag. Unset means a genuine first
     * run only (an existing install, or a restored backup, already has a layout); an explicit
     * false is "run setup again" from the about page, which shows it whatever the layout.
     */
    fun shouldShow(layoutSaved: Boolean, done: Boolean?): Boolean = when (done) {
        true -> false
        false -> true
        null -> !layoutSaved
    }
}

/**
 * Whether the setup overlay is up. Whether a layout existed is read once from the store, before
 * the default layout is first saved, so the setup stays up while it runs, until [finish].
 */
internal class OnboardingState(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val visible: StateFlow<Boolean> = flow {
        val layoutSaved = context.metroDataStore.data.first()[stringPreferencesKey("tiles")] != null
        emitAll(settings.onboardingDone.map { Onboarding.shouldShow(layoutSaved, it) })
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Skipping and finishing both end the setup for good. */
    fun finish() {
        scope.launch { settings.setOnboardingDone(true) }
    }
}
