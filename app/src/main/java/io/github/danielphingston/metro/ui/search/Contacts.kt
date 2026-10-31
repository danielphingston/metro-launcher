package io.github.danielphingston.metro.ui.search

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.graphics.asImageBitmap
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.LaunchTarget
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.PeopleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A contact as the list knows it; numbers and WhatsApp are looked up only for the few that match. */
internal data class ContactStub(val id: Long, val lookupKey: String?, val name: String, val photoUri: String?)

/** What a matched contact can be called, messaged and WhatsApp'd on. [numbers] are in the contact's own order, the primary first. */
internal data class ContactDetails(val numbers: List<String>, val whatsapp: WhatsAppRow?)

/** A contact's WhatsApp data row: [dataId] is the row, [mimeType] says which WhatsApp (see [WhatsAppApps]). */
internal data class WhatsAppRow(val dataId: Long, val mimeType: String)

/** Pure decisions for the contacts feature, kept apart from the content provider so they are tested. */
internal object ContactMatcher {
    private const val MIN_NUMBER_DIGITS = 3

    /** Digits of [text] only (a leading "+" is kept), so "+49 (170) 123-4567" and "491701234567" can be compared. */
    fun digits(text: String): String {
        val d = text.filter { it.isDigit() }
        return if (text.trimStart().startsWith("+")) "+$d" else d
    }

    /** Whether [query] is a phone number being typed: digits with the usual separators, at least three digits. */
    fun isNumberQuery(query: String): Boolean {
        val q = query.trim()
        return q.isNotEmpty() &&
            q.all { it.isDigit() || it in "+-() ." } &&
            q.count { it.isDigit() } >= MIN_NUMBER_DIGITS
    }

    /** Whether [number] contains the digits typed in [query]. */
    fun numberMatches(number: String, query: String): Boolean {
        val q = digits(query).removePrefix("+")
        return q.length >= MIN_NUMBER_DIGITS && digits(number).removePrefix("+").contains(q)
    }

    /** The best [limit] of [contacts] by name for [query]: exact, prefix, word start, initials, contains, typo. */
    fun byName(contacts: List<ContactStub>, query: String, limit: Int): List<ContactStub> =
        SearchRanking.top(contacts, query, limit) { listOf(it.name) }.map { it.item }

    /** The name part of a call/message phrase's contacts: ranked like a search. */
    fun forPhrase(contacts: List<ContactStub>, name: String, limit: Int) = byName(contacts, name, limit)
}

/** Intents for a contact's buttons; built without the phone so they are tested. */
internal object ContactIntents {
    /** Opens the dialler with [number] typed in; nothing is dialled until the user presses call (no phone permission needed). */
    fun dial(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))

    fun message(number: String): Intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))

    fun open(id: Long, lookupKey: String?): Intent = Intent(Intent.ACTION_VIEW, uri(id, lookupKey))

    /** The contact's lookup URI, which still finds it after a sync renumbers it; its plain URI without a lookup key. */
    fun uri(id: Long, lookupKey: String?): Uri =
        if (lookupKey != null) ContactsContract.Contacts.getLookupUri(id, lookupKey)
        else ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)

    /** The chat through the contact's own WhatsApp data row. */
    fun whatsAppRow(row: WhatsAppRow): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row.dataId), row.mimeType)
            .setPackage(WhatsAppApps.packageForMime(row.mimeType))

    /** The chat through WhatsApp's public link, for a number: https://wa.me/<digits>. */
    fun whatsAppLink(number: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${digits(number)}")).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun digits(number: String) = ContactMatcher.digits(number).removePrefix("+")
}

/** Reads the contacts. A seam so the provider is tested with a fake book. */
internal interface ContactsSource {
    suspend fun all(context: Context): List<ContactStub>
    suspend fun byNumber(context: Context, query: String): List<ContactStub>
    suspend fun details(context: Context, contact: ContactStub): ContactDetails
}

/** The phone's contacts. The list of names is kept briefly (one process-wide copy), so typing doesn't reread it. */
internal object SystemContacts : ContactsSource {
    private const val TTL_MILLIS = 60_000L
    private const val MAX_NUMBER_HITS = 4

    @Volatile
    private var cached: List<ContactStub>? = null

    @Volatile
    private var loadedAt = 0L

    override suspend fun all(context: Context): List<ContactStub> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - loadedAt < TTL_MILLIS } ?: runCatching { load(context) }.getOrNull()?.also {
            cached = it
            loadedAt = now
        // A failed read (permission not granted yet) is not kept: the contacts show as soon as it is granted.
        } ?: emptyList()
    }

    /** Throws when the contacts can't be read (no permission); a null cursor counts the same. */
    private fun load(context: Context): List<ContactStub> {
        val columns = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        return context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, columns, null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(2)?.trim().orEmpty()
                    if (name.isNotEmpty()) add(ContactStub(c.getLong(0), c.getString(1), name, c.getString(3)))
                }
            }
        } ?: error("contacts unavailable")
    }

    override suspend fun byNumber(context: Context, query: String): List<ContactStub> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
            val columns = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext() && size < MAX_NUMBER_HITS * 3) {
                        // A contact saved without a name is shown by its number rather than as an empty title.
                        add(ContactStub(c.getLong(0), c.getString(1), c.getString(2)?.trim().orEmpty().ifEmpty { c.getString(4).orEmpty() }, c.getString(3)))
                    }
                }.distinctBy { it.id }.take(MAX_NUMBER_HITS)
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun details(context: Context, contact: ContactStub): ContactDetails = withContext(Dispatchers.IO) {
        runCatching {
            val numbers = ArrayList<String>()
            var primary: String? = null
            var whatsapp: WhatsAppRow? = null
            val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            val mimes = listOf(phone) + WhatsAppApps.profileMimeTypes
            val placeholders = mimes.joinToString { "?" }
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.Data.IS_PRIMARY,
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
                (listOf(contact.id.toString()) + mimes).toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1)
                    if (mime == phone) {
                        val number = c.getString(2)?.trim().orEmpty()
                        if (number.isNotEmpty() && number !in numbers) numbers += number
                        if (c.getInt(3) != 0 && number.isNotEmpty() && primary == null) primary = number
                    } else if (whatsapp == null && mime != null) {
                        whatsapp = WhatsAppRow(c.getLong(0), mime)
                    }
                }
            }
            ContactDetails(primary?.let { listOf(it) + (numbers - it) } ?: numbers, whatsapp)
        }.getOrDefault(ContactDetails(emptyList(), null))
    }
}

private fun hasContactsPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** Feature 8: people by name or number, with a photo and call, message and WhatsApp buttons. */
internal class ContactsProvider(
    private val source: ContactsSource = SystemContacts,
    private val hasPermission: (Context) -> Boolean = ::hasContactsPermission,
    private val photo: suspend (Context, String) -> androidx.compose.ui.graphics.ImageBitmap? = ::loadPhoto,
) : SearchProvider {
    override val id = "contacts"
    override val feature = SmartFeature.CONTACTS
    override val section = "contacts"
    override val debounceMillis = 100L

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val context = ctx.context
        if (!hasPermission(context)) return emptyList()
        val hits = if (ContactMatcher.isNumberQuery(query)) {
            source.byNumber(context, query)
        } else {
            ContactMatcher.byName(source.all(context), query, MAX_ROWS)
        }
        return hits.take(MAX_ROWS).map { contact ->
            val details = source.details(context, contact)
            val number = details.numbers.firstOrNull()
            SearchItem(
                key = "c${contact.id}",
                title = contact.name,
                subtitle = number,
                icon = contact.photoUri?.let { photo(context, it) }?.let { SearchIcon.Image(it) } ?: SearchGlyphs.person,
                action = SearchActions.start(ContactIntents.open(contact.id, contact.lookupKey)),
                pin = TileContent.Action(
                    contact.name,
                    ActionIcon.Contact(ContactIntents.uri(contact.id, contact.lookupKey).toString()),
                    LaunchTarget.Activity(ContactIntents.open(contact.id, contact.lookupKey)),
                ),
                buttons = buttonsFor(number, details.whatsapp, details.whatsapp?.let { WhatsAppApps.icon(ctx.apps, WhatsAppApps.packageForMime(it.mimeType)) } ?: SearchGlyphs.chat),
            )
        }
    }

    companion object {
        private const val MAX_ROWS = 4

        /** Call and message need a number; WhatsApp only appears when the contact has a WhatsApp data row. */
        fun buttonsFor(number: String?, whatsapp: WhatsAppRow?, whatsAppIcon: SearchIcon = SearchGlyphs.chat): List<ItemButton> = buildList {
            if (number != null) {
                add(ItemButton("call", SearchGlyphs.phone, SearchActions.start(ContactIntents.dial(number))))
                add(ItemButton("message", SearchGlyphs.message, SearchActions.start(ContactIntents.message(number))))
            }
            if (whatsapp != null) {
                add(ItemButton("WhatsApp", whatsAppIcon, whatsAppAction(whatsapp, number)))
            }
        }

        /** Opens the contact's WhatsApp chat, through its data row, else (if that fails) through the wa.me link. */
        private fun whatsAppAction(row: WhatsAppRow, number: String?): ItemAction = ItemAction.Open { env ->
            val intents = listOfNotNull(ContactIntents.whatsAppRow(row), number?.let(ContactIntents::whatsAppLink))
            intents.any { intent ->
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).sourceBounds = env.bounds
                    env.context.startActivity(intent, env.options)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }
    }
}

private suspend fun loadPhoto(context: Context, uri: String) =
    PeopleSource.thumbnail(context, uri, 96)?.asImageBitmap()

/**
 * "call <name>" and "message <name>": the matching contacts that have a number, each a row that
 * opens the dialler or the messages app on it. Needs the contacts switch and permission like the
 * contacts rows do, and answers nothing otherwise.
 */
internal class ContactsPersonActions(
    private val source: ContactsSource = SystemContacts,
    private val hasPermission: (Context) -> Boolean = ::hasContactsPermission,
    private val photo: suspend (Context, String) -> androidx.compose.ui.graphics.ImageBitmap? = ::loadPhoto,
) : PersonActions {
    override suspend fun items(action: QuickAction.Person, ctx: SearchContext): List<SearchItem> {
        if (!ctx.settings.smartOn(SmartFeature.CONTACTS) || !hasPermission(ctx.context)) return emptyList()
        val call = action is QuickAction.Call
        return ContactMatcher.forPhrase(source.all(ctx.context), action.name, MAX_ROWS).mapNotNull { contact ->
            val number = source.details(ctx.context, contact).numbers.firstOrNull() ?: return@mapNotNull null
            val intent = if (call) ContactIntents.dial(number) else ContactIntents.message(number)
            SearchItem(
                key = "${if (call) "call" else "message"}:${contact.id}",
                title = "${if (call) "call" else "message"} ${contact.name}",
                subtitle = number,
                icon = contact.photoUri?.let { photo(ctx.context, it) }?.let { SearchIcon.Image(it) }
                    ?: if (call) SearchGlyphs.phone else SearchGlyphs.message,
                action = SearchActions.start(intent),
                prominent = true,
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 3
    }
}
