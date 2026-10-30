package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.HistoryData
import io.github.danielphingston.metro.data.HistoryRules
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.ui.search.ActionEnv
import io.github.danielphingston.metro.ui.search.CalculatorProvider
import io.github.danielphingston.metro.ui.search.AskAssistantProvider
import io.github.danielphingston.metro.ui.search.ItemAction
import io.github.danielphingston.metro.ui.search.LauncherActionsProvider
import io.github.danielphingston.metro.ui.search.LauncherAction
import io.github.danielphingston.metro.ui.search.LauncherActions
import io.github.danielphingston.metro.ui.search.LauncherCommands
import io.github.danielphingston.metro.ui.search.Placement
import io.github.danielphingston.metro.ui.search.PersonActions
import io.github.danielphingston.metro.ui.search.ProviderResults
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.QuickAction
import io.github.danielphingston.metro.ui.search.QuickActionParser
import io.github.danielphingston.metro.ui.search.QuickActionsProvider
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.search.SearchItem
import io.github.danielphingston.metro.ui.search.SearchProvider
import io.github.danielphingston.metro.ui.search.SearchProviders
import io.github.danielphingston.metro.ui.search.SearchRow
import io.github.danielphingston.metro.ui.search.SettingsShortcuts
import io.github.danielphingston.metro.ui.search.SettingsShortcutsProvider
import io.github.danielphingston.metro.ui.search.Suggestions
import io.github.danielphingston.metro.ui.search.TaggedItems
import io.github.danielphingston.metro.ui.search.searchRows
import io.github.danielphingston.metro.ui.search.topResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SmartProvidersTest {
    private fun app(label: String, uninstallable: Boolean = true) = AppInfo(
        key = AppKey("com.example.${label.lowercase().replace(' ', '.')}", "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = ComponentName("com.example", "Main"),
        isUninstallable = uninstallable,
        loadIcon = { _, _ -> ColorDrawable(0) },
    )

    private val apps = listOf(app("Chrome"), app("Camera"), app("Phone", uninstallable = false))
    private fun ctx(settings: LauncherSettings = LauncherSettings()) = SearchContext(RuntimeEnvironment.getApplication(), apps, settings)

    private class Commands : LauncherCommands {
        val calls = mutableListOf<String>()
        override fun editStart() { calls += "edit" }
        override fun appInfo(app: AppInfo) { calls += "info ${app.label}" }
        override fun uninstall(app: AppInfo) { calls += "uninstall ${app.label}" }
        override fun recordAssistantQuestion(question: String) { calls += "asked $question" }
    }

    private fun env(commands: LauncherCommands = Commands()) = ActionEnv(RuntimeEnvironment.getApplication(), null, null, commands)

    // --- gating ----------------------------------------------------------------------------

    @Test
    fun lockedMeansNoSmartProviderIsAsked() {
        val all = SearchProviders.all
        assertTrue(all.isNotEmpty())
        assertTrue(SearchProviders.active(LauncherSettings(), unlocked = false).isEmpty())
        val allOn = LauncherSettings(smartFeatures = SmartFeature.entries.toSet())
        assertEquals(all.size, SearchProviders.active(allOn, unlocked = true).size)
    }

    @Test
    fun aSwitchedOffFeatureIsNotAsked() {
        val settings = LauncherSettings(smartFeatures = SmartFeature.defaults - SmartFeature.CALCULATOR)
        val ids = SearchProviders.active(settings, true).map { it.id }
        assertFalse("calculator" in ids)
        assertTrue("quick_actions" in ids)
    }

    @Test
    fun providerIdsAreUniqueAndEveryFeatureHasAtMostOneSwitch() {
        val ids = SearchProviders.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(SmartFeature.entries.size, SmartFeature.entries.map { it.id }.toSet().size)
    }

    @Test
    fun defaultsMatchTheSpec() {
        val on = SmartFeature.defaults
        assertFalse(SmartFeature.CALENDAR in on)
        assertFalse(SmartFeature.CONTACTS in on)
        listOf(
            SmartFeature.CALCULATOR, SmartFeature.SETTINGS_SHORTCUTS, SmartFeature.QUICK_ACTIONS, SmartFeature.SUGGESTIONS,
            SmartFeature.LAUNCHER_ACTIONS, SmartFeature.WHATSAPP, SmartFeature.SEARCH_IN_APPS, SmartFeature.ASK_ASSISTANT,
        ).forEach { assertTrue(it.name, it in on) }
        assertEquals(11, SmartFeature.entries.size) // twelve features, one of them (matching) free and switchless
        assertTrue(SmartFeature.SEARCH_KEYWORDS in on)
        assertEquals("android.permission.READ_CALENDAR", SmartFeature.CALENDAR.permission)
        assertEquals("android.permission.READ_CONTACTS", SmartFeature.CONTACTS.permission)
    }

    // --- stale rows ------------------------------------------------------------------------

    private class Fake(override val id: String) : SearchProvider {
        override val feature: SmartFeature? = null
        override suspend fun results(query: String, ctx: SearchContext) = emptyList<SearchItem>()
    }

    private fun item(title: String) = SearchItem(title, title, null, SearchGlyphs.gear, ItemAction.Run { })

    @Test
    fun onlyResultsOfTheCurrentQueryAreVisible() {
        val a = Fake("a")
        val b = Fake("b")
        val tagged = mapOf("a" to TaggedItems("ab", listOf(item("old"))), "b" to TaggedItems("abc", listOf(item("new"))))
        val shown = ProviderResults.visible(listOf(a, b), tagged, "abc")
        assertEquals(listOf("b"), shown.map { it.provider.id })
        assertEquals(listOf("new"), shown.single().items.map { it.title })
        // The same query keeps its rows while the provider is asked again.
        assertEquals(1, ProviderResults.visible(listOf(a), tagged, "ab").size)
        assertTrue(ProviderResults.visible(emptyList(), tagged, "abc").isEmpty())
        assertTrue(ProviderResults.visible(listOf(a), emptyMap(), "abc").isEmpty())
    }

    @Test
    fun aProviderThatThrowsHasNoRows() = runBlocking {
        val broken = object : SearchProvider {
            override val id = "broken"
            override val feature: SmartFeature? = null
            override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> = error("boom")
        }
        assertTrue(ProviderResults.ask(broken, "x", ctx()).isEmpty())
    }

    // --- row order -------------------------------------------------------------------------

    private fun provider(id: String, placement: Placement, section: String? = null) = object : SearchProvider {
        override val id = id
        override val feature: SmartFeature? = null
        override val placement = placement
        override val section = section
        override suspend fun results(query: String, ctx: SearchContext) = emptyList<SearchItem>()
    }

    @Test
    fun providerRowsGoTopThenAppsThenMiddleThenWebThenBottom() {
        val top = provider("t", Placement.TOP)
        val mid = provider("m", Placement.MIDDLE, "settings")
        val bottom = provider("b", Placement.BOTTOM)
        val provided = listOf(bottom, mid, top).map { ProvidedItems(it, listOf(item(it.id))) }
        val rows = searchRows(apps, "ca", emptyList(), SearchEngine.GOOGLE, 0L, provided)
        val names = rows.map {
            when (it) {
                is SearchRow.Caption -> "# ${it.text}"
                is SearchRow.App -> it.app.label
                is SearchRow.Web -> "web"
                is SearchRow.Item -> "item ${it.item.title}"
                else -> "?"
            }
        }
        assertEquals(listOf("item t", "# apps", "Camera", "# settings", "item m", "# web", "web", "item b"), names)
    }

    @Test
    fun enterTakesATopProviderRowFirst() {
        val top = ProvidedItems(provider("t", Placement.TOP), listOf(item("answer")))
        val rows = searchRows(apps, "cam", emptyList(), SearchEngine.GOOGLE, 0L, listOf(top))
        assertEquals("answer", ((topResult(rows)) as SearchRow.Item).item.title)
        val mid = ProvidedItems(provider("m", Placement.MIDDLE), listOf(item("x")))
        assertTrue(topResult(searchRows(apps, "cam", emptyList(), SearchEngine.GOOGLE, 0L, listOf(mid))) is SearchRow.App)
    }

    // --- calculator provider ---------------------------------------------------------------

    @Test
    fun calculatorRowCopiesTheAnswer() = runBlocking {
        val rows = CalculatorProvider.results("15% of 2400", ctx())
        assertEquals("360", rows.single().title)
        assertTrue(rows.single().prominent)
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        (rows.single().action as ItemAction.Run).run(env())
        assertEquals("360", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("3.10686 mi", CalculatorProvider.results("5 km in miles", ctx()).single().title)
        assertTrue(CalculatorProvider.results("hello", ctx()).isEmpty())
    }

    // --- settings shortcuts ----------------------------------------------------------------

    private fun matches(q: String, available: (io.github.danielphingston.metro.ui.search.SettingsShortcut) -> Boolean = { true }) =
        SettingsShortcuts.match(q, available).map { it.title }

    @Test
    fun keywordsFindSettingsPages() {
        assertEquals("wi-fi", matches("wifi").first())
        assertEquals("bluetooth", matches("blue").first())
        assertEquals("battery", matches("batt").first())
        assertEquals("do not disturb", matches("dnd").first())
        assertEquals("date & time", matches("time zone").first())
        assertEquals("hotspot & tethering", matches("hotspot").first())
        assertEquals("nfc", matches("nfc").first())
        assertEquals("about phone", matches("about phone").first())
        assertEquals("accessibility", matches("accessibilty").first()) // a typo
    }

    @Test
    fun shortOrUnrelatedQueriesFindNothing() {
        assertTrue(matches("w").isEmpty())
        assertTrue(matches(" ").isEmpty())
        assertTrue(matches("zzzzqq").isEmpty())
        assertTrue(matches("fi").isEmpty() || matches("fi").first() != "xx")
        assertTrue(matches("ifi").isEmpty()) // inside a word is not enough
    }

    @Test
    fun atMostThreeAndOnlyAvailableOnes() {
        assertTrue(matches("s").isEmpty())
        assertTrue(matches("ba").size <= 3)
        assertEquals(emptyList<String>(), matches("wifi") { false })
        assertFalse("wi-fi" in matches("wifi") { it.title != "wi-fi" })
    }

    @Test
    fun providerOnlyShowsIntentsThatResolveAndDeveloperOptionsWhenEnabled() = runBlocking {
        var developer = false
        val resolved = mutableListOf<String?>()
        val provider = SettingsShortcutsProvider(
            resolves = { _, intent -> resolved += intent.action; intent.action != Settings.ACTION_BLUETOOTH_SETTINGS },
            developerOptionsOn = { developer },
        )
        assertEquals("wi-fi", provider.results("wifi", ctx()).first().title)
        assertTrue(provider.results("bluetooth", ctx()).isEmpty())
        assertTrue(provider.results("developer options", ctx()).none { it.title == "developer options" })
        developer = true
        assertEquals("developer options", provider.results("developer options", ctx()).first().title)
        val intent = (provider.results("wifi", ctx()).first().action as ItemAction.Start).intent
        assertEquals(Settings.ACTION_WIFI_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    // --- quick actions ---------------------------------------------------------------------

    @Test
    fun parsesTimers() {
        fun t(q: String) = (QuickActionParser.parse(q) as? QuickAction.Timer)?.seconds
        assertEquals(300, t("timer 5 min"))
        assertEquals(90, t("timer 90s"))
        assertEquals(300, t("timer 5"))
        assertEquals(5400, t("timer 1h30m"))
        assertEquals(5400, t("timer 1h 30"))
        assertEquals(4500, t("timer 1 hour 15 minutes"))
        assertEquals(330, t("timer 5 min 30 sec"))
        assertEquals(90, t("timer 1:30"))
        assertEquals(3600 + 1800, t("timer 1:30:00"))
        assertEquals(150, t("timer 2.5 min"))
        assertEquals(150, t("timer 2,5 min"))
        assertEquals(600, t("set a timer for 10 minutes"))
        assertEquals(600, t("10 min timer"))
        assertEquals(60, t("TIMER 1 MINUTE"))
        assertEquals(86400, t("timer 24h"))
        assertNull(t("timer 25h"))
        // Totals past Int range must be rejected, not wrap around into a valid-looking timer.
        assertNull(t("timer 99999999999999 h"))
        assertNull(t("timer 1" + "0".repeat(30) + " s"))
        assertNull(t("timer 1193046 h")) // 4,294,965,600 s: low 32 bits would be a small positive number
        assertNull(t("timer"))
        assertNull(t("timer 0"))
        assertNull(t("timer soon"))
        assertNull(t("timer 5 parsecs"))
        assertNull(t("timer 5 min 10 min"))
        assertNull(t("kitchen timer"))
        assertNull(t("timers 5"))
    }

    @Test
    fun parsesAlarms() {
        fun a(q: String) = (QuickActionParser.parse(q) as? QuickAction.Alarm)?.let { Triple(it.hour, it.minute, it.label) }
        assertEquals(Triple(7, 30, null), a("alarm 7:30"))
        assertEquals(Triple(6, 0, null), a("alarm 6am"))
        assertEquals(Triple(18, 45, null), a("alarm 18:45"))
        assertEquals(Triple(18, 30, null), a("alarm 6:30 pm"))
        assertEquals(Triple(0, 15, null), a("alarm 12:15 am"))
        assertEquals(Triple(12, 0, null), a("alarm 12pm"))
        assertEquals(Triple(7, 0, null), a("alarm 7"))
        assertEquals(Triple(7, 30, null), a("alarm 730"))
        assertEquals(Triple(18, 30, null), a("alarm 1830"))
        assertEquals(Triple(7, 30, "gym"), a("alarm 7:30 gym"))
        assertEquals(Triple(7, 30, "gym"), a("set alarm for 7:30 for gym"))
        assertNull(a("alarm 25:00"))
        assertNull(a("alarm 7:75"))
        assertNull(a("alarm 13pm"))
        assertNull(a("alarm 0am"))
        assertNull(a("alarm"))
        assertNull(a("alarm clock"))
        assertNull(a("alarm 7x"))
    }

    @Test
    fun parsesNotesAndPeople() {
        assertEquals(QuickAction.Note("buy milk"), QuickActionParser.parse("note buy milk"))
        assertEquals(QuickAction.Note("buy milk"), QuickActionParser.parse("note: buy milk"))
        assertNull(QuickActionParser.parse("note"))
        assertNull(QuickActionParser.parse("notes app"))
        assertEquals(QuickAction.Call("mom"), QuickActionParser.parse("call mom"))
        assertEquals(QuickAction.Message("Anna B"), QuickActionParser.parse("Message Anna B"))
        assertEquals(QuickAction.Message("dad"), QuickActionParser.parse("text dad"))
        assertNull(QuickActionParser.parse("call"))
        assertNull(QuickActionParser.parse("hello"))
    }

    @Test
    fun formatsDurationsAndTimes() {
        assertEquals("5 minutes", QuickActionParser.describe(300))
        assertEquals("1 hour 30 minutes", QuickActionParser.describe(5400))
        assertEquals("1 minute 30 seconds", QuickActionParser.describe(90))
        assertEquals("07:05", QuickActionParser.time(7, 5, true))
        assertEquals("7:05 am", QuickActionParser.time(7, 5, false))
        assertEquals("12:00 pm", QuickActionParser.time(12, 0, false))
        assertEquals("12:30 am", QuickActionParser.time(0, 30, false))
    }

    @Test
    fun quickActionRowsBuildTheRightIntents() = runBlocking {
        val provider = QuickActionsProvider(resolves = { _, _ -> true }, is24Hour = { true })
        val timer = provider.results("timer 5 min", ctx()).single()
        assertEquals("start a timer for 5 minutes", timer.title)
        val timerIntent = (timer.action as ItemAction.Start).intent
        assertEquals(AlarmClock.ACTION_SET_TIMER, timerIntent.action)
        assertEquals(300, timerIntent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertFalse(timerIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))

        val alarm = provider.results("alarm 6am gym", ctx()).single()
        val alarmIntent = (alarm.action as ItemAction.Start).intent
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarmIntent.action)
        assertEquals(6, alarmIntent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(0, alarmIntent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("gym", alarmIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals("set an alarm for 06:00", alarm.title)

        val note = (provider.results("note buy milk", ctx()).single().action as ItemAction.Start).intent
        assertEquals(Intent.ACTION_CHOOSER, note.action)
        val inner = note.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("text/plain", inner.type)
        assertEquals("buy milk", inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun quickActionsNeedAnAppThatHandlesThem() = runBlocking {
        val none = QuickActionsProvider(resolves = { _, _ -> false })
        assertTrue(none.results("timer 5 min", ctx()).isEmpty())
        assertTrue(none.results("alarm 7", ctx()).isEmpty())
        assertTrue(none.results("note x", ctx()).isEmpty())
    }

    @Test
    fun callAndMessageGoToThePersonActionsHook() = runBlocking {
        assertTrue(QuickActionsProvider(resolves = { _, _ -> true }).results("call mom", ctx()).isEmpty())
        val seen = mutableListOf<QuickAction.Person>()
        val provider = QuickActionsProvider(personActions = PersonActions { action, _ -> seen += action; listOf(item(action.name)) })
        assertEquals(listOf("mom"), provider.results("call mom", ctx()).map { it.title })
        provider.results("message dad", ctx())
        assertEquals(listOf<QuickAction.Person>(QuickAction.Call("mom"), QuickAction.Message("dad")), seen)
    }

    // --- launcher actions ------------------------------------------------------------------

    @Test
    fun parsesLauncherActions() {
        fun cmds(q: String) = LauncherActions.parse(q).filterIsInstance<LauncherAction.Command>().map { it.command }
        assertEquals(LauncherActions.Cmd.WALLPAPER, cmds("wallpaper").first())
        assertEquals(LauncherActions.Cmd.EDIT_TILES, cmds("edit tiles").first())
        assertEquals(LauncherActions.Cmd.EDIT_TILES, cmds("edit start").first())
        assertEquals(LauncherActions.Cmd.METRO_SETTINGS, cmds("metro settings").first())
        assertEquals(LauncherActions.Cmd.SMART_SEARCH_SETTINGS, cmds("smart search settings").first())
        assertEquals(LauncherAction.ForApp(LauncherAction.Verb.APP_INFO, "chrome"), LauncherActions.parse("app info chrome").single())
        assertEquals(LauncherAction.ForApp(LauncherAction.Verb.UNINSTALL, "chrome"), LauncherActions.parse("Uninstall chrome").single())
        assertTrue(LauncherActions.parse("uninstall").isEmpty())
        assertTrue(LauncherActions.parse("x").isEmpty())
        assertTrue(LauncherActions.parse("zebra").isEmpty())
    }

    @Test
    fun uninstallOffersOnlyAppsThatCanBeUninstalled() {
        assertEquals(listOf("Chrome"), LauncherActions.appsFor(LauncherAction.Verb.UNINSTALL, "chrome", apps).map { it.label })
        assertTrue(LauncherActions.appsFor(LauncherAction.Verb.UNINSTALL, "phone", apps).isEmpty())
        assertEquals(listOf("Phone"), LauncherActions.appsFor(LauncherAction.Verb.APP_INFO, "phone", apps).map { it.label })
    }

    @Test
    fun launcherActionRowsRunCommands() = runBlocking {
        val provider = LauncherActionsProvider(resolves = { _, _ -> true })
        val commands = Commands()
        (provider.results("edit tiles", ctx()).first().action as ItemAction.Run).run(env(commands))
        (provider.results("app info chrome", ctx()).single().action as ItemAction.Run).run(env(commands))
        (provider.results("uninstall chrome", ctx()).single().action as ItemAction.Run).run(env(commands))
        assertEquals(listOf("edit", "info Chrome", "uninstall Chrome"), commands.calls)
        val wallpaper = (provider.results("wallpaper", ctx()).first().action as ItemAction.Start).intent
        assertEquals(Intent.ACTION_CHOOSER, wallpaper.action)
        assertTrue(LauncherActionsProvider(resolves = { _, _ -> false }).results("wallpaper", ctx()).isEmpty())
        val settings = (provider.results("smart search settings", ctx()).first().action as ItemAction.Start).intent
        assertEquals("SEARCH", settings.getStringExtra("page"))
    }

    // --- history and suggestions -----------------------------------------------------------

    @Test
    fun recentSearchesAreTidyUniqueAndLimited() {
        var list = emptyList<String>()
        list = HistoryRules.addSearch(list, "  maps   go ")
        assertEquals(listOf("maps go"), list)
        list = HistoryRules.addSearch(list, "x")
        assertEquals(listOf("maps go"), list)
        list = HistoryRules.addSearch(list, "calendar")
        list = HistoryRules.addSearch(list, "MAPS GO")
        assertEquals(listOf("MAPS GO", "calendar"), list)
        (1..12).forEach { list = HistoryRules.addSearch(list, "query $it") }
        assertEquals(io.github.danielphingston.metro.data.LauncherSettings.DEFAULT_SEARCH_HISTORY, list.size)
        assertEquals("query 12", list.first())
        assertEquals(HistoryRules.MAX_SEARCH_LENGTH, HistoryRules.addSearch(emptyList(), "a".repeat(500)).single().length)
    }

    @Test
    fun searchHistorySizeIsClampedAndTrims() {
        val l = io.github.danielphingston.metro.data.LauncherSettings
        assertEquals(5, l.DEFAULT_SEARCH_HISTORY)
        assertEquals(1, l.clampSearchHistory(0))
        assertEquals(1, l.clampSearchHistory(-7))
        assertEquals(25, l.clampSearchHistory(99))
        assertEquals(12, l.clampSearchHistory(12))
        var list = emptyList<String>()
        (1..30).forEach { list = HistoryRules.addSearch(list, "query $it", 12) }
        assertEquals(12, list.size)
        (1..30).forEach { list = HistoryRules.addSearch(list, "other $it", 99) }
        assertEquals(25, list.size)
        assertEquals(listOf("other 30", "other 29"), HistoryRules.trim(list, 2))
        assertEquals(1, HistoryRules.trim(list, 0).size)
        assertEquals(list, HistoryRules.trim(list, 25))
    }

    @Test
    fun mostUsedUsesCountsThenRecency() {
        val chrome = apps[0]
        val camera = apps[1]
        val data = HistoryData(
            launchCounts = mapOf(chrome.key.serialize() to 5, camera.key.serialize() to 5),
            lastLaunched = mapOf(chrome.key.serialize() to 10L, camera.key.serialize() to 20L),
        )
        assertEquals(listOf("Camera", "Chrome"), HistoryRules.mostUsed(apps, data).map { it.label })
        val more = data.copy(launchCounts = data.launchCounts + (apps[2].key.serialize() to 9), lastLaunched = data.lastLaunched + (apps[2].key.serialize() to 1L))
        assertEquals("Phone", HistoryRules.mostUsed(apps, more).first().label)
        assertEquals(1, HistoryRules.mostUsed(apps, more, limit = 1).size)
        // Only launch times known: most recent first.
        val recent = HistoryData(lastLaunched = mapOf(chrome.key.serialize() to 1L, camera.key.serialize() to 2L))
        assertEquals(listOf("Camera", "Chrome"), HistoryRules.mostUsed(apps, recent).map { it.label })
        assertTrue(HistoryRules.mostUsed(apps, HistoryData()).isEmpty())
    }

    @Test
    fun emptyQueryShowsSuggestionsOtherwiseRecentlyAdded() {
        val s = Suggestions(listOf(apps[0]), listOf("maps", "mail"))
        val rows = searchRows(apps, "  ", emptyList(), SearchEngine.GOOGLE, 0L, suggestions = s)
        assertEquals(
            listOf("c:most used", "a:" + apps[0].key.serialize(), "c:recent searches", "q:maps", "q:mail", "clear-history"),
            rows.map { it.key },
        )
        assertTrue(searchRows(emptyList(), "", emptyList(), SearchEngine.GOOGLE, 0L, suggestions = Suggestions(emptyList(), emptyList())).isEmpty())
        // Typing hides the suggestions.
        assertTrue(searchRows(apps, "ca", emptyList(), SearchEngine.GOOGLE, 0L, suggestions = s).none { it is SearchRow.RecentSearch })
    }

    @Test
    fun aQuestionThatCouldNotBeSentIsNotRemembered() {
        // No such package: nothing can be opened, so the assistant tile must not claim it was asked.
        val missing = app("Ghost")
        val settings = LauncherSettings(assistant = missing.key.packageName)
        val item = kotlinx.coroutines.runBlocking {
            AskAssistantProvider.results("hello", SearchContext(RuntimeEnvironment.getApplication(), listOf(missing), settings)).single()
        }
        val commands = Commands()
        val opened = (item.action as ItemAction.Open).run(env(commands))
        assertFalse(opened)
        assertTrue(commands.calls.none { it.startsWith("asked") })
    }
}
