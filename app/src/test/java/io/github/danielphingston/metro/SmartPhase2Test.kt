package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Process
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SearchInApp
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.ui.search.AmazonDomain
import io.github.danielphingston.metro.ui.search.CalendarHit
import io.github.danielphingston.metro.ui.search.CalendarMatcher
import io.github.danielphingston.metro.ui.search.CalendarProvider
import io.github.danielphingston.metro.ui.search.ChatHit
import io.github.danielphingston.metro.ui.search.ContactDetails
import io.github.danielphingston.metro.ui.search.ContactIntents
import io.github.danielphingston.metro.ui.search.ContactMatcher
import io.github.danielphingston.metro.ui.search.ContactStub
import io.github.danielphingston.metro.ui.search.ContactsPersonActions
import io.github.danielphingston.metro.ui.search.ContactsProvider
import io.github.danielphingston.metro.ui.search.ContactsSource
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.search.Placement
import io.github.danielphingston.metro.ui.search.ProvidedItems
import io.github.danielphingston.metro.ui.search.QuickAction
import io.github.danielphingston.metro.ui.search.QuickActionsProvider
import io.github.danielphingston.metro.ui.search.SearchContext
import io.github.danielphingston.metro.ui.search.SearchInIntents
import io.github.danielphingston.metro.ui.search.SearchInAppsProvider
import io.github.danielphingston.metro.ui.search.SearchInPhone
import io.github.danielphingston.metro.ui.search.SearchProviders
import io.github.danielphingston.metro.ui.search.SearchRow
import io.github.danielphingston.metro.ui.search.ShortcutHit
import io.github.danielphingston.metro.ui.search.WhatsAppApps
import io.github.danielphingston.metro.ui.search.WhatsAppMatcher
import io.github.danielphingston.metro.ui.search.WhatsAppProvider
import io.github.danielphingston.metro.ui.search.WhatsAppRow
import io.github.danielphingston.metro.ui.search.searchRows
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SmartPhase2Test {
    private fun app(label: String, pkg: String = "com.example.${label.lowercase().replace(' ', '.')}") = AppInfo(
        key = AppKey(pkg, "Main", 0),
        label = label,
        user = Process.myUserHandle(),
        component = ComponentName(pkg, "Main"),
        isUninstallable = true,
        loadIcon = { _, _ -> ColorDrawable(0) },
    )

    private fun ctx(settings: LauncherSettings = LauncherSettings(), apps: List<AppInfo> = emptyList(), now: Long = 0L) =
        SearchContext(RuntimeEnvironment.getApplication(), apps, settings, Locale.ENGLISH) { now }

    // --- Amazon domain -------------------------------------------------------------------------

    @Test
    fun amazonDomainFollowsSimThenNetworkThenLocale() {
        assertEquals("amazon.in", AmazonDomain.choose("in", "us", "de"))
        assertEquals("amazon.de", AmazonDomain.choose("", "DE", "us"))
        assertEquals("amazon.co.uk", AmazonDomain.choose(null, null, "GB"))
        assertEquals("amazon.co.uk", AmazonDomain.choose(null, null, "uk"))
        assertEquals("amazon.co.jp", AmazonDomain.choose(" jp ", null, null))
    }

    @Test
    fun amazonDomainDefaultsToCom() {
        assertEquals("amazon.com", AmazonDomain.choose(null, null, null))
        assertEquals("amazon.com", AmazonDomain.choose("", " ", ""))
        assertEquals("amazon.com", AmazonDomain.choose("zz", "de", "de")) // the SIM decides, even without a store
        assertEquals("amazon.com", AmazonDomain.choose("us", null, "de"))
    }

    // --- intents -------------------------------------------------------------------------------

    @Test
    fun youtubeIntents() {
        val app = SearchInIntents.youtubeApp("cats & dogs")
        assertEquals(Intent.ACTION_SEARCH, app.action)
        assertEquals("com.google.android.youtube", app.`package`)
        assertEquals("cats & dogs", app.getStringExtra(android.app.SearchManager.QUERY))
        val web = SearchInIntents.youtubeWeb("cats & dogs")
        assertEquals("https://www.youtube.com/results?search_query=cats%20%26%20dogs", web.dataString)
        assertNull(web.`package`)
    }

    @Test
    fun storeMapsAndSpotifyIntents() {
        assertEquals("market://search?q=a%2Bb", SearchInIntents.playStore("a+b").dataString)
        assertEquals("com.android.vending", SearchInIntents.playStore("x").`package`)
        assertEquals("geo:0,0?q=pizza%20near%20me", SearchInIntents.maps("pizza near me").dataString)
        assertEquals("spotify:search:daft%20punk", SearchInIntents.spotify("daft punk").dataString)
        assertEquals("com.spotify.music", SearchInIntents.spotify("x").`package`)
    }

    @Test
    fun amazonIntentUsesTheStoreAndOptionallyTheApp() {
        val web = SearchInIntents.amazon("usb c cable", "amazon.in", app = false)
        assertEquals("https://www.amazon.in/s?k=usb%20c%20cable", web.dataString)
        assertNull(web.`package`)
        assertEquals(SearchInIntents.AMAZON_PACKAGE, SearchInIntents.amazon("x", "amazon.de", app = true).`package`)
    }

    @Test
    fun otherAppIntents() {
        val plain = SearchInIntents.other("com.example.notes", "milk", web = false)
        assertEquals(Intent.ACTION_SEARCH, plain.action)
        assertEquals("com.example.notes", plain.`package`)
        assertEquals("milk", plain.getStringExtra(android.app.SearchManager.QUERY))
        assertEquals(Intent.ACTION_WEB_SEARCH, SearchInIntents.other("p", "q", web = true).action)
    }

    // --- search in apps provider ---------------------------------------------------------------

    private class FakePhone(val domain: String = "amazon.com", val answers: (Intent) -> Boolean) : SearchInPhone {
        override fun resolves(intent: Intent) = answers(intent)
        override fun amazonDomain(locale: Locale) = domain
    }

    private fun searchRows(settings: LauncherSettings, phone: SearchInPhone, others: List<OtherSearchApp> = emptyList(), apps: List<AppInfo> = emptyList()) =
        runBlocking {
            SearchInAppsProvider({ phone }, { _, _ -> others }).results("cats", ctx(settings, apps))
        }

    @Test
    fun searchInAppsDefaultsAreYouTubeAndPlayStore() {
        assertEquals(setOf(SearchInApp.YOUTUBE, SearchInApp.PLAY_STORE), LauncherSettings().searchInApps)
        assertEquals(emptySet<String>(), LauncherSettings().searchInOtherApps)
        val rows = searchRows(LauncherSettings(), FakePhone { true })
        assertEquals(listOf("youtube", "play_store"), rows.map { it.key })
        assertEquals("search YouTube for “cats”", rows[0].title)
    }

    @Test
    fun youtubeFallsBackToTheBrowserWhenTheAppIsMissing() {
        val missing = FakePhone { it.`package` == null }
        val row = searchRows(LauncherSettings(searchInApps = setOf(SearchInApp.YOUTUBE)), missing).single()
        val intent = (row.action as io.github.danielphingston.metro.ui.search.ItemAction.Start).intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.youtube.com/results?search_query=cats", intent.dataString)
    }

    @Test
    fun youtubeUsesTheAppWhenInstalled() {
        val row = searchRows(LauncherSettings(searchInApps = setOf(SearchInApp.YOUTUBE)), FakePhone { true }).single()
        val intent = (row.action as io.github.danielphingston.metro.ui.search.ItemAction.Start).intent
        assertEquals("com.google.android.youtube", intent.`package`)
    }

    @Test
    fun amazonUsesTheShoppingAppElseTheBrowserOnTheCountryStore() {
        val only = LauncherSettings(searchInApps = setOf(SearchInApp.AMAZON))
        fun intentOf(phone: SearchInPhone) =
            (searchRows(only, phone).single().action as io.github.danielphingston.metro.ui.search.ItemAction.Start).intent
        val withApp = intentOf(FakePhone("amazon.in") { true })
        assertEquals(SearchInIntents.AMAZON_PACKAGE, withApp.`package`)
        assertEquals("https://www.amazon.in/s?k=cats", withApp.dataString)
        val browser = intentOf(FakePhone("amazon.de") { it.`package` == null })
        assertNull(browser.`package`)
        assertEquals("https://www.amazon.de/s?k=cats", browser.dataString)
    }

    @Test
    fun uninstalledAppsGetNoRow() {
        val all = LauncherSettings(searchInApps = SearchInApp.entries.toSet())
        val keys = searchRows(all, FakePhone { it.`package` == null }).map { it.key }
        // Play Store, Spotify (package-bound) are gone; YouTube and Amazon fall back to the web, maps resolves without a package.
        assertEquals(listOf("youtube", "maps", "amazon"), keys)
    }

    @Test
    fun switchedOffAppsAreNotShown() {
        assertTrue(searchRows(LauncherSettings(searchInApps = emptySet()), FakePhone { true }).isEmpty())
    }

    @Test
    fun otherAppsNeedTheirOwnSwitchAndToResolve() {
        val notes = OtherSearchApp("com.example.notes", "Notes", web = false)
        val browser = OtherSearchApp("com.example.browser", "Browser", web = true)
        val settings = LauncherSettings(searchInApps = emptySet(), searchInOtherApps = setOf("com.example.notes", "com.example.browser"))
        val rows = searchRows(settings, FakePhone { it.`package` == "com.example.notes" }, listOf(notes, browser))
        assertEquals(listOf("other:com.example.notes"), rows.map { it.key })
        val off = searchRows(LauncherSettings(searchInApps = emptySet()), FakePhone { true }, listOf(notes))
        assertTrue(off.isEmpty())
    }

    @Test
    fun rowsUseTheInstalledAppsIcon() {
        val youtube = app("YouTube", "com.google.android.youtube")
        val row = searchRows(LauncherSettings(searchInApps = setOf(SearchInApp.YOUTUBE)), FakePhone { true }, apps = listOf(youtube)).single()
        assertEquals(io.github.danielphingston.metro.ui.search.SearchIcon.App(youtube), row.icon)
    }

    // --- contacts ------------------------------------------------------------------------------

    private val anna = ContactStub(1, "k1", "Anna Brandt", null)
    private val bob = ContactStub(2, "k2", "Bob Annan", null)
    private val carl = ContactStub(3, "k3", "Carl Vogel", null)

    @Test
    fun contactNamesRankLikeSearch() {
        val book = listOf(bob, carl, anna)
        assertEquals(listOf(anna, bob), ContactMatcher.byName(book, "ann", 5)) // prefix beats word-prefix... of "Annan"? both match; alphabetical within rank
        assertEquals(listOf(carl), ContactMatcher.byName(book, "cv", 5)) // initials
        assertEquals(listOf(carl), ContactMatcher.byName(book, "vogel", 5)) // surname
        assertEquals(listOf(anna), ContactMatcher.byName(book, "Anna Brandt", 5))
        assertTrue(ContactMatcher.byName(book, "zzz", 5).isEmpty())
        assertTrue(ContactMatcher.byName(book, "  ", 5).isEmpty())
        assertEquals(1, ContactMatcher.byName(book, "an", 1).size)
    }

    @Test
    fun numberQueriesAndMatching() {
        assertTrue(ContactMatcher.isNumberQuery("0170"))
        assertTrue(ContactMatcher.isNumberQuery("+49 170 12"))
        assertTrue(ContactMatcher.isNumberQuery("(555) 123-4567"))
        assertFalse(ContactMatcher.isNumberQuery("12"))
        assertFalse(ContactMatcher.isNumberQuery("anna 123"))
        assertFalse(ContactMatcher.isNumberQuery(""))
        assertEquals("+491701234567", ContactMatcher.digits("+49 (170) 123-4567"))
        assertEquals("5551234", ContactMatcher.digits("555-1234"))
        assertTrue(ContactMatcher.numberMatches("+49 170 1234567", "170 123"))
        assertTrue(ContactMatcher.numberMatches("(555) 123-4567", "5551234"))
        assertFalse(ContactMatcher.numberMatches("+49 170 1234567", "999"))
        assertFalse(ContactMatcher.numberMatches("+49 170 1234567", "17")) // too short to mean anything
    }

    @Test
    fun contactIntents() {
        val dial = ContactIntents.dial("+49 170 1")
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel", dial.data?.scheme)
        assertEquals("+49 170 1", dial.data?.schemeSpecificPart)
        assertEquals("smsto", ContactIntents.message("0170").data?.scheme)
        assertEquals(Intent.ACTION_SENDTO, ContactIntents.message("0170").action)
        val row = ContactIntents.whatsAppRow(WhatsAppRow(42, "vnd.android.cursor.item/vnd.com.whatsapp.profile"))
        assertEquals("com.whatsapp", row.`package`)
        assertEquals("vnd.android.cursor.item/vnd.com.whatsapp.profile", row.type)
        assertTrue(row.dataString!!.endsWith("/data/42"))
        assertEquals("com.whatsapp.w4b", ContactIntents.whatsAppRow(WhatsAppRow(1, WhatsAppApps.profileMimeTypes[1])).`package`)
        assertEquals("https://wa.me/491701234567", ContactIntents.whatsAppLink("+49 170 1234567").dataString)
    }

    @Test
    fun contactButtonsDependOnWhatIsKnown() {
        val wa = WhatsAppRow(5, WhatsAppApps.profileMimeTypes[0])
        assertEquals(listOf("call", "message", "WhatsApp"), ContactsProvider.buttonsFor("0170", wa).map { it.label })
        assertEquals(listOf("call", "message"), ContactsProvider.buttonsFor("0170", null).map { it.label })
        assertEquals(listOf("WhatsApp"), ContactsProvider.buttonsFor(null, wa).map { it.label })
        assertTrue(ContactsProvider.buttonsFor(null, null).isEmpty())
    }

    private class FakeBook(val people: Map<ContactStub, ContactDetails>) : ContactsSource {
        override suspend fun all(context: Context) = people.keys.toList()
        override suspend fun byNumber(context: Context, query: String) =
            people.filter { (_, d) -> d.numbers.any { ContactMatcher.numberMatches(it, query) } }.keys.toList()
        override suspend fun details(context: Context, contact: ContactStub) = people.getValue(contact)
    }

    private val book = FakeBook(
        mapOf(
            anna to ContactDetails(listOf("+49 170 1234567"), WhatsAppRow(7, WhatsAppApps.profileMimeTypes[0])),
            carl to ContactDetails(emptyList(), null),
        ),
    )

    @Test
    fun contactsProviderReturnsNothingWithoutPermission() {
        val provider = ContactsProvider(book, hasPermission = { false }, photo = { _, _ -> null })
        assertTrue(runBlocking { provider.results("anna", ctx()) }.isEmpty())
    }

    @Test
    fun contactsProviderFindsByNameAndNumber() {
        val provider = ContactsProvider(book, hasPermission = { true }, photo = { _, _ -> null })
        val byName = runBlocking { provider.results("anna", ctx()) }.single()
        assertEquals("Anna Brandt", byName.title)
        assertEquals("+49 170 1234567", byName.subtitle)
        assertEquals(listOf("call", "message", "WhatsApp"), byName.buttons.map { it.label })
        val byNumber = runBlocking { provider.results("170 123", ctx()) }
        assertEquals(listOf("Anna Brandt"), byNumber.map { it.title })
        val noNumber = runBlocking { provider.results("carl", ctx()) }.single()
        assertTrue(noNumber.buttons.isEmpty())
        assertNull(noNumber.subtitle)
    }

    @Test
    fun personActionsFeedCallAndMessagePhrases() {
        val actions = ContactsPersonActions(book, hasPermission = { true }, photo = { _, _ -> null })
        val on = LauncherSettings(smartFeatures = SmartFeature.defaults + SmartFeature.CONTACTS)
        val call = runBlocking { actions.items(QuickAction.Call("anna"), ctx(on)) }.single()
        assertEquals("call Anna Brandt", call.title)
        assertEquals(Intent.ACTION_DIAL, ((call.action) as io.github.danielphingston.metro.ui.search.ItemAction.Start).intent.action)
        val message = runBlocking { actions.items(QuickAction.Message("anna"), ctx(on)) }.single()
        assertEquals(Intent.ACTION_SENDTO, ((message.action) as io.github.danielphingston.metro.ui.search.ItemAction.Start).intent.action)
        // A contact without a number can't be called.
        assertTrue(runBlocking { actions.items(QuickAction.Call("carl"), ctx(on)) }.isEmpty())
    }

    @Test
    fun personActionsNeedTheSwitchAndPermission() {
        val off = LauncherSettings() // contacts are off by default
        val actions = ContactsPersonActions(book, hasPermission = { true }, photo = { _, _ -> null })
        assertTrue(runBlocking { actions.items(QuickAction.Call("anna"), ctx(off)) }.isEmpty())
        val denied = ContactsPersonActions(book, hasPermission = { false }, photo = { _, _ -> null })
        val on = LauncherSettings(smartFeatures = SmartFeature.defaults + SmartFeature.CONTACTS)
        assertTrue(runBlocking { denied.items(QuickAction.Call("anna"), ctx(on)) }.isEmpty())
    }

    @Test
    fun callPhraseReachesTheContactsThroughTheQuickActionsProvider() {
        val on = LauncherSettings(smartFeatures = SmartFeature.defaults + SmartFeature.CONTACTS)
        val provider = QuickActionsProvider(ContactsPersonActions(book, { true }, { _, _ -> null }))
        assertEquals("call Anna Brandt", runBlocking { provider.results("call anna", ctx(on)) }.single().title)
    }

    // --- WhatsApp ------------------------------------------------------------------------------

    @Test
    fun whatsAppDetection() {
        assertTrue(WhatsAppApps.isWhatsApp("com.whatsapp"))
        assertTrue(WhatsAppApps.isWhatsApp("com.whatsapp.w4b"))
        assertFalse(WhatsAppApps.isWhatsApp("com.whatsapp.fake"))
        assertFalse(WhatsAppApps.isWhatsApp("org.telegram.messenger"))
        assertTrue(WhatsAppApps.isProfileMime("vnd.android.cursor.item/vnd.com.whatsapp.profile"))
        assertFalse(WhatsAppApps.isProfileMime("vnd.android.cursor.item/phone_v2"))
        assertFalse(WhatsAppApps.isProfileMime(null))
        assertEquals("com.whatsapp.w4b", WhatsAppApps.packageForMime(WhatsAppApps.profileMimeTypes[1]))
        assertEquals("com.whatsapp", WhatsAppApps.packageForMime(WhatsAppApps.profileMimeTypes[0]))
    }

    @Test
    fun conversationShortcutsAreTellableFromActions() {
        assertTrue(WhatsAppApps.isConversation(false, setOf("android.shortcut.conversation")))
        assertTrue(WhatsAppApps.isConversation(true, null))
        assertFalse(WhatsAppApps.isConversation(false, setOf("other")))
        assertFalse(WhatsAppApps.isConversation(false, null))
    }

    private val family = ChatHit("com.whatsapp", "g1", 0, "Family group", "Family group")
    private val dentist = ChatHit("com.whatsapp", "c2", 0, "Dr. Dentist", null)

    @Test
    fun chatsMatchByLabel() {
        val chats = listOf(dentist, family)
        assertEquals(listOf(family), WhatsAppMatcher.top(chats, "fam", 4))
        assertEquals(listOf(dentist), WhatsAppMatcher.top(chats, "dentist", 4))
        assertEquals(listOf(family), WhatsAppMatcher.top(chats, "fg", 4)) // initials
        assertTrue(WhatsAppMatcher.top(chats, "zzz", 4).isEmpty())
    }

    @Test
    fun whatsAppProviderMakesRowsWithTheShortcutKey() {
        val provider = WhatsAppProvider(chats = { _ -> { q, n -> WhatsAppMatcher.top(listOf(dentist, family), q, n) } }, iconOf = { _, _ -> null })
        val wa = app("WhatsApp", "com.whatsapp")
        val item = runBlocking { provider.results("family", ctx(apps = listOf(wa))) }.single()
        assertEquals("Family group", item.title)
        assertEquals("WhatsApp", item.subtitle)
        assertEquals("com.whatsapp/g1#0", item.shortcutKey)
        assertEquals(io.github.danielphingston.metro.ui.search.SearchIcon.App(wa), item.icon)
    }

    @Test
    fun aShortcutAlreadyListedIsNotListedAgainByWhatsApp() {
        val provider = WhatsAppProvider(chats = { _ -> { _, _ -> listOf(dentist, family) } }, iconOf = { _, _ -> null })
        val items = runBlocking { provider.results("d", ctx()) }
        val provided = listOf(ProvidedItems(provider, items))
        val hit = ShortcutHit("com.whatsapp", "c2", 0, "Dr. Dentist", null)
        fun rows(shortcuts: List<ShortcutHit>) = searchRows(emptyList(), "d", shortcuts, SearchEngine.GOOGLE, 0L, provided)
        val withShortcut = rows(listOf(hit))
        assertEquals(0, withShortcut.count { it is SearchRow.Item && it.item.title == "Dr. Dentist" })
        assertEquals(1, withShortcut.count { it is SearchRow.Shortcut })
        // The other chat has no shortcut row, so it stays; a caption shows only with rows under it.
        assertEquals(1, withShortcut.count { it is SearchRow.Item && it.item.title == "Family group" })
        assertEquals(1, withShortcut.count { it is SearchRow.Caption && it.text == "whatsapp" })
        val without = rows(emptyList())
        assertEquals(2, without.count { it is SearchRow.Item })
        // Only the dentist matched and is listed as a shortcut: the whole section disappears.
        val onlyDentist = searchRows(emptyList(), "d", listOf(hit), SearchEngine.GOOGLE, 0L, listOf(ProvidedItems(provider, items.filter { it.title == "Dr. Dentist" })))
        assertFalse(onlyDentist.any { it is SearchRow.Caption && it.text == "whatsapp" })
    }

    // --- calendar ------------------------------------------------------------------------------

    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun event(id: Long, title: String, begin: Long, location: String? = null, allDay: Boolean = false, hours: Long = 1) =
        CalendarHit(id, title, begin, begin + hours * 3_600_000, begin, begin + hours * 3_600_000, allDay, location)

    @Test
    fun eventsMatchTitleOrLocationSoonestFirst() {
        val events = listOf(
            event(1, "Dentist", at(5, 9, month = 11), "Main Street 4"),
            event(2, "Team lunch", at(2, 12, month = 11), "Dentist & Co"),
            event(3, "Dentist follow-up", at(1, 9, month = 11)),
            event(4, "Gym", at(1, 18, month = 11)),
        )
        // Exact title beats prefix beats a place; "Dentist & Co" matches as a prefix of the location.
        assertEquals(listOf(1L, 3L, 2L), CalendarMatcher.top(events, "dentist", 5).map { it.eventId })
        assertEquals(listOf(1L), CalendarMatcher.top(events, "main street", 5).map { it.eventId })
        assertEquals(2, CalendarMatcher.top(events, "dentist", 2).size)
        assertTrue(CalendarMatcher.top(events, "", 5).isEmpty())
        assertTrue(CalendarMatcher.top(events, "zzz", 5).isEmpty())
    }

    @Test
    fun eventTimesAreWorded() {
        val now = at(30, 10)
        fun text(hit: CalendarHit, is24: Boolean = true) = CalendarMatcher.whenText(hit, now, utc, Locale.ENGLISH, is24)
        assertEquals("today 14:00", text(event(1, "a", at(30, 14))))
        assertEquals("today 2:00 pm", text(event(1, "a", at(30, 14)), is24 = false))
        assertEquals("tomorrow 09:30", text(event(1, "a", at(31, 9, 30))))
        assertEquals("sat 14 nov 09:00", text(event(1, "a", at(14, 9, month = 11))))
        assertEquals("tomorrow, all day", text(event(1, "a", at(31, 0), allDay = true, hours = 24)))
        assertEquals("today, all day", text(event(1, "a", at(30, 0), allDay = true, hours = 24)))
        assertEquals("now until 11:00", text(event(1, "a", at(30, 9), hours = 2)))
    }

    @Test
    fun openIntentCarriesTheEventAndItsTimes() {
        val e = event(77, "a", 1000, allDay = true)
        val intent = CalendarMatcher.openIntent(e)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.dataString!!.endsWith("/events/77"))
        assertEquals(1000L, intent.getLongExtra("beginTime", 0))
        assertTrue(intent.getBooleanExtra("allDay", false))
    }

    @Test
    fun calendarProviderNeedsPermission() {
        val events = listOf(event(1, "Dentist", at(31, 9), "Main Street"))
        val source = io.github.danielphingston.metro.ui.search.CalendarEventsSource { _, _ -> events }
        val denied = CalendarProvider(source, hasPermission = { false }, is24Hour = { true }, zone = { utc })
        assertTrue(runBlocking { denied.results("dentist", ctx(now = at(30, 10))) }.isEmpty())
        val granted = CalendarProvider(source, hasPermission = { true }, is24Hour = { true }, zone = { utc })
        val row = runBlocking { granted.results("dentist", ctx(now = at(30, 10))) }.single()
        assertEquals("Dentist", row.title)
        assertEquals("tomorrow 09:00 · Main Street", row.subtitle)
    }

    // --- registry ------------------------------------------------------------------------------

    @Test
    fun newProvidersAreRegisteredWithTheirPlacement() {
        val byId = SearchProviders.all.associateBy { it.id }
        assertEquals(Placement.MIDDLE, byId.getValue("contacts").placement)
        assertEquals(Placement.MIDDLE, byId.getValue("whatsapp").placement)
        assertEquals(Placement.MIDDLE, byId.getValue("calendar").placement)
        assertEquals(Placement.BOTTOM, byId.getValue("search_in_apps").placement)
        // Contacts and calendar are off until the user switches them on; the rest are on.
        val ids = SearchProviders.active(LauncherSettings(), unlocked = true).map { it.id }
        assertFalse("contacts" in ids)
        assertFalse("calendar" in ids)
        assertTrue("whatsapp" in ids && "search_in_apps" in ids)
        assertTrue(SearchProviders.active(LauncherSettings(), unlocked = false).none { it.feature != null })
    }
}
