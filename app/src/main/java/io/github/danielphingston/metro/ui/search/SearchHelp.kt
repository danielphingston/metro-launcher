package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.data.SmartFeature

/**
 * What smart search understands, as phrases to type: the "what can I type?" settings page lists
 * every [groups] entry, and the empty search shows a few [examples] of the features that are on.
 */
internal object SearchHelp {
    /** A phrase to type and what it does. */
    data class Example(val phrase: String, val note: String)

    /** The phrases of one [feature], under its [title]. */
    data class Group(val feature: SmartFeature, val title: String, val examples: List<Example>)

    val groups: List<Group> = listOf(
        Group(
            SmartFeature.CALCULATOR, "sums & units",
            listOf(
                Example("15% of 2400", "percentages"),
                Example("5x3+2", "sums, with x or * for times"),
                Example("5 km in miles", "lengths, weights, temperatures and more"),
                Example("100 mbps in mb/s", "data sizes and speeds"),
                Example("3 months in days", "time spans"),
            ),
        ),
        Group(
            SmartFeature.CALCULATOR, "dates",
            listOf(
                Example("days till nov 3", "how long until a date"),
                Example("in 10 days", "the date and weekday 10 days from now"),
                Example("what day is christmas", "the weekday of a date or holiday"),
                Example("next friday", "the date of a weekday"),
                Example("3 weeks ago", "the date back then"),
            ),
        ),
        Group(
            SmartFeature.QUICK_ACTIONS, "quick actions",
            listOf(
                Example("timer 5 min", "starts a timer; also 5 min timer"),
                Example("alarm 7:30", "sets an alarm; also alarm 730 or alarm 6 pm gym"),
                Example("note: buy milk", "saves a note"),
                Example("call mom", "calls a contact or number; also dial, ring"),
                Example("text mom", "writes a message; also message, sms"),
            ),
        ),
        Group(
            SmartFeature.SETTINGS_SHORTCUTS, "phone settings",
            listOf(
                Example("wifi", "opens that page of the phone's settings"),
                Example("battery", "also bluetooth, hotspot, storage, dnd"),
            ),
        ),
        Group(
            SmartFeature.LAUNCHER_ACTIONS, "launcher actions",
            listOf(
                Example("app info camera", "the system page of an app"),
                Example("uninstall camera", "removes an app"),
                Example("wallpaper", "changes the wallpaper; also edit tiles"),
            ),
        ),
        Group(
            SmartFeature.SEARCH_KEYWORDS, "search keywords",
            listOf(
                Example("yt cats", "searches YouTube"),
                Example("a headphones", "searches Amazon"),
                Example("m coffee", "searches Maps"),
                Example("ai plan my week", "asks your assistant"),
            ),
        ),
        Group(
            SmartFeature.CONTACTS, "contacts",
            listOf(Example("mom", "a person, to call, message or WhatsApp")),
        ),
        Group(
            SmartFeature.WHATSAPP, "WhatsApp",
            listOf(Example("family", "opens a chat or group by its name")),
        ),
        Group(
            SmartFeature.CALENDAR, "calendar",
            listOf(Example("dentist", "upcoming events with that title or place")),
        ),
    )

    /**
     * Up to [count] phrases for the empty search, one per group whose feature [isOn], taking turns
     * day by day ([day] is any day number) so the same few stay put while search is open.
     */
    fun examples(isOn: (SmartFeature) -> Boolean, day: Long, count: Int = 4): List<Example> =
        groups
            .filter { isOn(it.feature) && it.examples.size > 1 }
            .let { on -> if (on.isEmpty()) emptyList() else List(minOf(count, on.size)) { on[((day + it) % on.size).toInt()] } }
            .map { it.examples[(day % it.examples.size).toInt()] }
}
