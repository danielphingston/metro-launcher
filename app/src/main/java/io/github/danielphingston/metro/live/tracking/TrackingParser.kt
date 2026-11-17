package io.github.danielphingston.metro.live.tracking

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** What a live tracking notification is about; decides how it is read and kept. */
enum class TrackKind { RIDE, FOOD, PARCEL, OTHER }

/** How far a parcel is. Only [ARRIVING_TODAY] and [OUT_FOR_DELIVERY] are shown; [DELIVERED] ends one. */
enum class ParcelStage(val id: String) {
    ARRIVING_TODAY("today"),
    OUT_FOR_DELIVERY("out"),
    DELIVERED("delivered"),
    ;

    companion object {
        fun fromId(id: String?): ParcelStage? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A progress bar as the app sent it: [value] of [max], with Android 16's segment lengths and
 * point positions when the app gave them (same scale as [value]).
 */
data class TrackProgress(val value: Int, val max: Int, val segments: List<Int> = emptyList(), val points: List<Int> = emptyList()) {
    val fraction: Float get() = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)

    /** Segment ends as fractions of the bar, without the last one (the bar's end). */
    val segmentEnds: List<Float>
        get() {
            if (max <= 0 || segments.size < 2) return emptyList()
            var sum = 0
            return segments.dropLast(1).map { sum += it; (sum.toFloat() / max).coerceIn(0f, 1f) }
        }

    val pointFractions: List<Float> get() = if (max <= 0) emptyList() else points.map { (it.toFloat() / max).coerceIn(0f, 1f) }

    companion object {
        /** The bar for these raw values, or null when there is no usable one (indeterminate, empty, nonsense). */
        fun of(value: Int, max: Int, indeterminate: Boolean, segments: List<Int> = emptyList(), points: List<Int> = emptyList()): TrackProgress? {
            if (indeterminate || max <= 0 || max > 1_000_000 || value < 0) return null
            val segs = segments.filter { it > 0 }.take(MAX_PARTS)
            val pts = points.filter { it in 0..max }.take(MAX_PARTS)
            return TrackProgress(value.coerceAtMost(max), max, segs, pts)
        }

        private const val MAX_PARTS = 12
    }
}

/**
 * The plain values the listener reads from one notification, free of framework objects so that
 * [TrackingParser] can be unit tested. [countdownTo] is the notification's `when` if it shows a
 * count-down chronometer; [secret] is VISIBILITY_SECRET.
 */
data class TrackInput(
    val key: String,
    val packageName: String,
    val appKey: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val postTime: Long,
    val ongoing: Boolean = false,
    val promoted: Boolean = false,
    val category: String? = null,
    val countdownTo: Long? = null,
    val progress: TrackProgress? = null,
    val shortCriticalText: String? = null,
    val secret: Boolean = false,
)

/**
 * One trip, order or parcel. Times are absolute ([etaAt]) so the tile can count down without
 * reparsing. [status] and [detail] are the app's own words; the tile decides whether they may show.
 */
data class TrackItem(
    val key: String,
    val packageName: String,
    val appKey: String,
    val kind: TrackKind,
    val status: String,
    val detail: String,
    val postTime: Long,
    val etaAt: Long? = null,
    val progress: TrackProgress? = null,
    val otp: String? = null,
    val plate: String? = null,
    val stage: ParcelStage? = null,
    val secret: Boolean = false,
    /** Android 16's short critical text ("5 min"), shown when there is no ETA. */
    val shortText: String? = null,
)

/** Which apps post tracking notifications, and what about. */
object TrackingApps {
    private val RIDES = setOf(
        "com.ubercab", "com.olacabs.customer", "com.rapido.passenger", "in.juspay.nammayatri",
        "me.lyft.android", "ee.mtakso.client", "com.grabtaxi.passenger",
    )
    private val FOOD = setOf(
        "in.swiggy.android", "com.application.zomato", "com.ubercab.eats", "com.grofers.customerapp",
        "com.zeptoconsumerapp", "com.bigbasket.mobileapp", "com.dunzo.user", "com.dd.doordash",
        "com.grubhub.android", "com.deliveroo.orderapp", "com.global.foodpanda.android", "com.zomato.district",
    )
    private val PARCELS = setOf(
        "in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "com.flipkart.android",
        "com.myntra.android", "com.meesho.supply", "com.ril.ajio", "com.fsn.nykaa", "com.snapdeal.main",
        "com.shopify.arrive", "com.tatacliq", "com.jio.mart",
    )

    /** Notification categories that are never a trip or delivery, even from an unknown app's Live Update. */
    private val NOT_TRACKING = setOf(
        "call", "alarm", "stopwatch", "navigation", "transport", "service", "sys", "recommendation",
        "reminder", "event", "email", "msg", "social", "promo", "missed_call", "workout", "location_sharing",
    )

    fun isKnown(pkg: String): Boolean = pkg in RIDES || pkg in FOOD || pkg in PARCELS

    /**
     * The kind for a notification from [pkg], or null when it is not one to track. Uber posts rides
     * and Eats orders from one app, so its text decides. An unknown app counts only with a Live
     * Update the system promoted, outside the categories that are never a trip.
     */
    fun kindOf(pkg: String, text: String, promoted: Boolean, category: String?): TrackKind? = when {
        pkg == "com.ubercab" -> if (FOOD_WORDS.containsMatchIn(text) && !RIDE_WORDS.containsMatchIn(text)) TrackKind.FOOD else TrackKind.RIDE
        pkg in RIDES -> TrackKind.RIDE
        pkg in FOOD -> TrackKind.FOOD
        pkg in PARCELS -> TrackKind.PARCEL
        promoted && category !in NOT_TRACKING -> TrackKind.OTHER
        else -> null
    }

    private val FOOD_WORDS = Regex("(?i)\\b(order|food|restaurant|eats|delivery partner|courier)\\b")
    private val RIDE_WORDS = Regex("(?i)\\b(driver|ride|trip|pickup|pick-up|cab|car|auto|moto)\\b")
}

/** Pure reading of tracking notifications: no Android types, so every rule has a unit test. */
object TrackingParser {
    /** An ETA further back than this is stale (the app stopped updating it) and is dropped. */
    const val STALE_MS = 10 * 60_000L

    /** ETAs further ahead than this are not a live trip's countdown. */
    private const val MAX_AHEAD_MS = 12 * 3_600_000L

    /** The trip, order or parcel [input] describes, or null when it is none (an offer, a finished trip). */
    fun parse(input: TrackInput, now: Long, zone: ZoneId = ZoneId.systemDefault()): TrackItem? {
        val title = clean(input.title)
        val text = clean(input.text)
        val sub = clean(input.subText)
        val critical = input.shortCriticalText?.let(::clean)?.takeIf { it.isNotEmpty() }
        val all = listOf(title, text, sub, critical.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n")
        val kind = TrackingApps.kindOf(input.packageName, all, input.promoted, input.category) ?: return null
        val otp = otpIn(all, allowPin = kind != TrackKind.PARCEL)
        val base = TrackItem(
            key = input.key,
            packageName = input.packageName,
            appKey = input.appKey,
            kind = kind,
            status = title.ifEmpty { text.lineSequence().firstOrNull().orEmpty() },
            detail = if (title.isEmpty()) "" else text,
            postTime = input.postTime,
            progress = input.progress,
            otp = otp,
            secret = input.secret,
            shortText = critical,
        )
        if (kind == TrackKind.PARCEL) {
            val stage = stageOf(all) ?: return null
            return base.copy(stage = stage, etaAt = null, plate = null)
        }
        // A finished trip or delivered order is over, whatever else the notification says.
        if (isFinished(all) && !input.ongoing && input.progress == null) return null
        val eta = etaOf(input, critical, all, now, zone)
        val live = input.ongoing || input.promoted || input.progress != null || eta != null || otp != null
        if (!live) return null
        val plate = if (kind == TrackKind.RIDE || kind == TrackKind.OTHER) plateIn(all) else null
        return base.copy(etaAt = eta, plate = plate)
    }

    /** The ETA as an absolute time: the countdown chronometer, else "N min" (critical text first), else a clock time. */
    internal fun etaOf(input: TrackInput, critical: String?, text: String, now: Long, zone: ZoneId): Long? {
        input.countdownTo?.let { at -> if (at > now - STALE_MS && at < now + MAX_AHEAD_MS) return at }
        // Relative times are relative to when the app last posted, not to when this rebuild ran.
        val from = input.postTime.takeIf { it in 1..now } ?: now
        critical?.let { minutesIn(it, bare = true) }?.let { return from + it * 60_000L }
        minutesIn(text, bare = false)?.let { return from + it * 60_000L }
        clockTimeIn(text)?.let { t -> return nextOccurrence(t, from, zone)?.takeIf { it - from < MAX_AHEAD_MS } }
        return null
    }

    private val MINUTE_UNIT = "(?:minutes?|mins?|m)\\b"
    private val RANGE = Regex("(?i)\\b(\\d{1,3})\\s*(?:-|–|to)\\s*(\\d{1,3})\\s*$MINUTE_UNIT")
    private val HOURS_MINUTES = Regex("(?i)\\b(\\d{1,2})\\s*(?:hours?|hrs?|h)\\s*(?:(\\d{1,2})\\s*$MINUTE_UNIT)?")
    private val MINUTES_CUE = Regex(
        "(?i)(?:\\bin|\\bwithin|\\beta:?|\\baway in|\\breach(?:ing|es)? in|\\barriv(?:e|es|ing) in)\\s*(?:about\\s+|~\\s*)?(\\d{1,3})\\s*$MINUTE_UNIT",
    )
    private val MINUTES_AWAY = Regex("(?i)\\b(\\d{1,3})\\s*(?:minutes?|mins?)\\s*(?:away|left|to go|more)\\b")
    private val BARE_MINUTES = Regex("(?i)^\\s*(\\d{1,3})\\s*$MINUTE_UNIT")

    /**
     * Minutes to go in [text]: "in 5 min", "arriving in 12 minutes", "8 mins away", "25-30 mins" (the
     * later end). [bare] also accepts a plain "5 min", as Android 16's short critical text is.
     */
    internal fun minutesIn(text: String, bare: Boolean): Int? {
        RANGE.find(text)?.let { m ->
            val hi = maxOf(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            return hi.takeIf { it in 0..180 }
        }
        MINUTES_CUE.find(text)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 0..180 } }
        MINUTES_AWAY.find(text)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 0..180 } }
        if (bare) {
            BARE_MINUTES.find(text)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 0..180 } }
            HOURS_MINUTES.find(text)?.let { m ->
                val total = m.groupValues[1].toInt() * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
                return total.takeIf { it in 1..12 * 60 }
            }
        }
        return null
    }

    private val CLOCK_12 = Regex("(?i)\\b(?:at|by|around|before|eta)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*([ap])\\.?\\s*m\\b\\.?")
    private val CLOCK_24 = Regex("(?i)\\b(?:at|by|around|before|eta)\\s*([01]?\\d|2[0-3]):([0-5]\\d)\\b(?!\\s*[ap]\\.?m)")

    /** "arriving at 7:45 PM", "by 9 pm", "ETA 19:40". */
    internal fun clockTimeIn(text: String): LocalTime? {
        CLOCK_12.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 1..12 || min !in 0..59) return null
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            return LocalTime.of(h % 12 + if (pm) 12 else 0, min)
        }
        CLOCK_24.find(text)?.let { m -> return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        return null
    }

    /** The first moment at [time] not long before [from], in [zone]. */
    internal fun nextOccurrence(time: LocalTime, from: Long, zone: ZoneId): Long? = try {
        val start = Instant.ofEpochMilli(from).atZone(zone)
        var at: ZonedDateTime = start.with(time)
        if (at.toInstant().toEpochMilli() < from - STALE_MS) at = at.plusDays(1)
        at.toInstant().toEpochMilli()
    } catch (_: RuntimeException) {
        null
    }

    private val OTP = Regex("(?i)\\b(?:otp|one[- ]time (?:password|pin|code)|start code|ride pin|pin|code)\\b(?!\\s*code)[^\\d\\n]{0,24}?(\\d{4,6})\\b")
    private val OTP_STRICT = Regex("(?i)\\b(?:otp|one[- ]time (?:password|pin|code)|delivery code)\\b[^\\d\\n]{0,24}?(\\d{4,6})\\b")

    /**
     * The one-time code in [text]: digits after "OTP", "PIN" or "code". [allowPin] is off for parcels,
     * where "PIN" is more often the postal code.
     */
    internal fun otpIn(text: String, allowPin: Boolean = true): String? {
        for (match in (if (allowPin) OTP else OTP_STRICT).findAll(text)) {
            // "PIN code 560001" is a postal code, not a PIN.
            val before = text.substring(maxOf(0, match.range.first - 8), match.range.first)
            if (POSTAL_BEFORE.containsMatchIn(before) || POSTAL.containsMatchIn(match.value)) continue
            return match.groupValues[1]
        }
        return null
    }

    private val POSTAL_BEFORE = Regex("(?i)\\b(pin|zip|postal)\\s*$")
    private val POSTAL = Regex("(?i)pin\\s*code|pincode|postal")

    /** The two-letter codes of Indian states and union territories that start a registration plate. */
    private val STATES = setOf(
        "AN", "AP", "AR", "AS", "BR", "CH", "CG", "DD", "DL", "DN", "GA", "GJ", "HR", "HP", "JK", "JH", "KA", "KL",
        "LA", "LD", "MP", "MH", "MN", "ML", "MZ", "NL", "OD", "OR", "PY", "PB", "RJ", "SK", "TN", "TS", "TG", "TR",
        "UP", "UK", "UA", "WB",
    )
    private val PLATE = Regex("(?i)\\b([A-Z]{2})[\\s-]?(\\d{1,2})[\\s-]?([A-Z]{1,3})[\\s-]?(\\d{1,4})\\b")
    private val BH_PLATE = Regex("(?i)\\b(\\d{2})[\\s-]?BH[\\s-]?(\\d{4})[\\s-]?([A-Z]{1,2})\\b")

    /** An Indian vehicle registration in [text], written the usual way: "KA 01 AB 1234", "22 BH 1234 AA". */
    internal fun plateIn(text: String): String? {
        BH_PLATE.find(text)?.let { m -> return "${m.groupValues[1]} BH ${m.groupValues[2]} ${m.groupValues[3].uppercase(Locale.ROOT)}" }
        for (m in PLATE.findAll(text)) {
            val state = m.groupValues[1].uppercase(Locale.ROOT)
            if (state !in STATES) continue
            val series = m.groupValues[3].uppercase(Locale.ROOT)
            val number = m.groupValues[4]
            // Real plates keep their letters upper case; "in 5 min" style text must not pass.
            if (m.value.any { it.isLowerCase() } || number.length < 3) continue
            return "$state ${m.groupValues[2].padStart(2, '0')} $series ${number.padStart(4, '0')}"
        }
        return null
    }

    private val WILL_BE_DELIVERED = Regex("(?i)\\b(?:will|to|would|should|could|may|might|can|being)\\s+(?:be\\s+)?delivered\\b|\\bbe delivered\\b")
    private val DELIVERED = Regex("(?i)\\bdelivered\\b")
    private val OUT_FOR_DELIVERY = Regex("(?i)\\bout for delivery\\b")
    private val TODAY = Regex("(?i)\\btoday\\b")
    private val ARRIVING = Regex("(?i)\\b(arriv\\w*|deliver\\w*|expected|on (?:its|the) way|reach\\w*|coming)\\b")
    private val NOT_TODAY = Regex("(?i)\\b(tomorrow|was due today|missed|could not|couldn't|failed|attempt(?:ed)? failed|delayed)\\b")

    /** How far a parcel is by its text: delivered, out for delivery, arriving today, or null for anything else. */
    internal fun stageOf(text: String): ParcelStage? {
        val stripped = WILL_BE_DELIVERED.replace(text, " ")
        if (DELIVERED.containsMatchIn(stripped)) return ParcelStage.DELIVERED
        if (NOT_TODAY.containsMatchIn(text)) return null
        if (OUT_FOR_DELIVERY.containsMatchIn(text)) return ParcelStage.OUT_FOR_DELIVERY
        if (TODAY.containsMatchIn(text) && ARRIVING.containsMatchIn(text)) return ParcelStage.ARRIVING_TODAY
        return null
    }

    private val FINISHED = Regex(
        "(?i)\\b(trip (?:has )?ended|trip completed|ride completed|ride (?:has )?ended|you(?:'ve| have) arrived|rate your|how was your|order (?:was |has been )?delivered|enjoy your meal|cancel+ed)\\b",
    )

    /** A trip or order that is over ("rate your ride", "order delivered", "cancelled"). */
    internal fun isFinished(text: String): Boolean = FINISHED.containsMatchIn(text) ||
        DELIVERED.containsMatchIn(WILL_BE_DELIVERED.replace(text, " "))

    /** Text as the tile shows it: one space between words, no control characters, a sane length. */
    internal fun clean(text: String): String = text
        .replace(Regex("[\\p{Cc}&&[^\\n]]"), " ")
        .lines().joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
        .take(MAX_TEXT)

    private const val MAX_TEXT = 300
}
