package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.data.LauncherSettings

/*
 * HOW TO ADD A SMART SEARCH FEATURE
 *
 *  1. Add an entry to SmartFeature (data/SmartSearch.kt): id, title, description, default, and the
 *     permission it asks for when switched on, if any. The settings list, the locked list and the
 *     stored switch (SettingsRepository.setSmartFeature) pick it up by themselves.
 *  2. Write one file in this package with a class or object implementing SearchProvider: set
 *     `feature`, a `placement` (TOP / MIDDLE / BOTTOM), an optional `section` caption, and return
 *     SearchItems from `results`. It runs off the main thread and is cancelled on the next
 *     keystroke. Needs a permission? Check it and return nothing when it is missing.
 *  3. Add ONE line to `all` below. The order of that list is the order of the rows within a placement.
 *
 * Nothing else changes: the overlay renders SearchItems generically (icon, title, subtitle, buttons)
 * and runs their ItemAction. Parse and match in pure functions next to the provider and test those.
 * Manifest <queries> entries for packages or intents the provider resolves go in AndroidManifest.xml.
 *
 * Providers are only asked while Metro Plus is unlocked and the feature's switch is on, see [active].
 */
internal object SearchProviders {
    val all: List<SearchProvider> = listOf(
        CalculatorProvider,
        QuickActionsProvider(ContactsPersonActions()),
        SettingsShortcutsProvider(),
        LauncherActionsProvider(),
        ContactsProvider(),
        WhatsAppProvider(),
        CalendarProvider(),
        SearchInAppsProvider(),
        AskAssistantProvider,
    )

    /** The providers that may answer now: always-on ones, and those whose feature is unlocked and switched on. */
    fun active(settings: LauncherSettings, unlocked: Boolean, providers: List<SearchProvider> = all): List<SearchProvider> =
        providers.filter { provider ->
            val feature = provider.feature
            feature == null || (unlocked && settings.smartOn(feature))
        }
}
