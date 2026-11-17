package io.github.danielphingston.metro

import io.github.danielphingston.metro.live.tracking.ParcelStage
import io.github.danielphingston.metro.live.tracking.TrackInput
import io.github.danielphingston.metro.live.tracking.TrackKind
import io.github.danielphingston.metro.live.tracking.TrackProgress
import io.github.danielphingston.metro.live.tracking.TrackingApps
import io.github.danielphingston.metro.live.tracking.TrackingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The pure reading of ride, food and parcel notifications. */
class TrackingParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** 2026-11-17 18:00 in India. */
    private val now = LocalDateTime.of(2026, 11, 17, 18, 0).atZone(zone).toInstant().toEpochMilli()

    private fun input(
        pkg: String,
        title: String,
        text: String = "",
        ongoing: Boolean = true,
        countdownTo: Long? = null,
        progress: TrackProgress? = null,
        critical: String? = null,
        promoted: Boolean = false,
        category: String? = null,
        secret: Boolean = false,
        postTime: Long = now - 60_000,
    ) = TrackInput(
        key = "0|$pkg|1|null|10123", packageName = pkg, appKey = "$pkg/0", title = title, text = text,
        postTime = postTime, ongoing = ongoing, promoted = promoted, category = category, countdownTo = countdownTo,
        progress = progress, shortCriticalText = critical, secret = secret,
    )

    private fun parse(i: TrackInput) = TrackingParser.parse(i, now, zone)

    // ---- rides ----

    @Test
    fun uberRideWithCountdownOtpAndPlate() {
        val item = parse(
            input(
                "com.ubercab", "Driver arriving",
                "Ramesh · White Swift Dzire KA 01 AB 1234. Share PIN 4821 to start your trip",
                countdownTo = now + 4 * 60_000,
            ),
        )!!
        assertEquals(TrackKind.RIDE, item.kind)
        assertEquals("Driver arriving", item.status)
        assertEquals(now + 4 * 60_000, item.etaAt)
        assertEquals("4821", item.otp)
        assertEquals("KA 01 AB 1234", item.plate)
        assertNull(item.stage)
    }

    @Test
    fun theCountdownBeatsTheTextAndTextMinutesCountFromThePostTime() {
        val posted = now - 3 * 60_000
        val text = parse(input("com.rapido.passenger", "Captain is on the way", "Arriving in 7 mins", postTime = posted))!!
        assertEquals(posted + 7 * 60_000, text.etaAt)
        val both = parse(input("com.rapido.passenger", "Captain is on the way", "Arriving in 7 mins", countdownTo = now + 60_000))!!
        assertEquals(now + 60_000, both.etaAt)
    }

    @Test
    fun androidShortCriticalTextIsAnEta() {
        val item = parse(input("com.olacabs.customer", "Your cab is arriving", critical = "5 min"))!!
        assertEquals(now - 60_000 + 5 * 60_000, item.etaAt)
        assertEquals("5 min", item.shortText)
    }

    @Test
    fun aStaleCountdownIsDropped() {
        val item = parse(input("com.ubercab", "Driver arriving", countdownTo = now - 20 * 60_000))!!
        assertNull(item.etaAt)
    }

    @Test
    fun aFinishedTripIsNotTracked() {
        assertNull(parse(input("com.ubercab", "Trip completed", "Rate your driver", ongoing = false)))
        assertNull(parse(input("com.olacabs.customer", "Ride cancelled", "Your ride was cancelled", ongoing = false)))
    }

    @Test
    fun anOfferFromARideAppIsNotTracked() {
        assertNull(parse(input("com.ubercab", "Weekend plans?", "Get 20% off your next 3 rides", ongoing = false)))
    }

    // ---- food ----

    @Test
    fun swiggyOrderWithAndroid16Progress() {
        val bar = TrackProgress.of(225, 400, false, segments = listOf(100, 100, 100, 100), points = listOf(0, 100, 200, 400))
        val item = parse(
            input("in.swiggy.android", "Out for delivery", "Suresh is on the way · Arriving in 25-30 mins", progress = bar),
        )!!
        assertEquals(TrackKind.FOOD, item.kind)
        // A range counts to its later end.
        assertEquals(now - 60_000 + 30 * 60_000, item.etaAt)
        assertEquals(bar, item.progress)
        assertNull(item.plate)
    }

    @Test
    fun uberEatsInsideTheUberAppIsFood() {
        val item = parse(input("com.ubercab", "Your order is on the way", "Courier is 6 minutes away"))!!
        assertEquals(TrackKind.FOOD, item.kind)
        assertEquals(now - 60_000 + 6 * 60_000, item.etaAt)
    }

    @Test
    fun aDeliveredOrderIsOver() {
        assertNull(parse(input("com.application.zomato", "Order delivered", "Enjoy your meal!", ongoing = false)))
        // "will be delivered" is still on its way.
        assertNotNull(parse(input("com.application.zomato", "On the way", "Your order will be delivered in 12 mins", ongoing = false)))
    }

    // ---- parcels ----

    @Test
    fun parcelStages() {
        assertEquals(ParcelStage.OUT_FOR_DELIVERY, TrackingParser.stageOf("Out for delivery: boAt Airdopes 141"))
        assertEquals(ParcelStage.ARRIVING_TODAY, TrackingParser.stageOf("Arriving today by 9 PM"))
        assertEquals(ParcelStage.ARRIVING_TODAY, TrackingParser.stageOf("Your package will be delivered today"))
        assertEquals(ParcelStage.DELIVERED, TrackingParser.stageOf("Delivered: your package was handed to you"))
        assertEquals(ParcelStage.DELIVERED, TrackingParser.stageOf("Your order has been delivered"))
        assertNull(TrackingParser.stageOf("Arriving tomorrow by 9 PM"))
        assertNull(TrackingParser.stageOf("Shipped: arriving Friday"))
        assertNull(TrackingParser.stageOf("Delivery attempt failed today"))
        assertNull(TrackingParser.stageOf("Big sale today only!"))
    }

    @Test
    fun aParcelNotificationIsReadWithItsStageAndNoPlate() {
        val item = parse(input("in.amazon.mShop.android.shopping", "Out for delivery", "boAt Airdopes 141 · OTP 3355 for delivery", ongoing = false))!!
        assertEquals(TrackKind.PARCEL, item.kind)
        assertEquals(ParcelStage.OUT_FOR_DELIVERY, item.stage)
        assertEquals("3355", item.otp)
        assertNull(item.plate)
        assertNull(item.etaAt)
        // An offer from a shopping app is not a parcel.
        assertNull(parse(input("com.flipkart.android", "Big Billion Days", "Deals end today", ongoing = false)))
    }

    // ---- other apps ----

    @Test
    fun anUnknownAppCountsOnlyWithAPromotedLiveUpdate() {
        assertNull(parse(input("com.example.pizza", "Pizza in the oven", ongoing = true)))
        val promoted = parse(input("com.example.pizza", "Pizza in the oven", critical = "12 min", promoted = true, category = "progress"))!!
        assertEquals(TrackKind.OTHER, promoted.kind)
        // Calls, navigation and timers are Live Updates too, but not trips.
        assertNull(parse(input("com.example.maps", "Turn left", promoted = true, category = "navigation")))
        assertNull(parse(input("com.example.phone", "Ongoing call", promoted = true, category = "call")))
    }

    @Test
    fun appKinds() {
        assertEquals(TrackKind.RIDE, TrackingApps.kindOf("com.rapido.passenger", "", false, null))
        assertEquals(TrackKind.FOOD, TrackingApps.kindOf("com.zeptoconsumerapp", "", false, null))
        assertEquals(TrackKind.PARCEL, TrackingApps.kindOf("com.flipkart.android", "", false, null))
        assertEquals(TrackKind.RIDE, TrackingApps.kindOf("com.ubercab", "Your driver is arriving", false, null))
        assertNull(TrackingApps.kindOf("com.whatsapp", "in 5 min", false, null))
    }

    // ---- text clues ----

    @Test
    fun otps() {
        assertEquals("4821", TrackingParser.otpIn("Share OTP 4821 with your driver"))
        assertEquals("4821", TrackingParser.otpIn("OTP: 4821"))
        assertEquals("1234", TrackingParser.otpIn("Your PIN is 1234"))
        assertEquals("998877", TrackingParser.otpIn("Delivery code 998877"))
        assertNull(TrackingParser.otpIn("Arriving in 12 min"))
        // A postal code is not a PIN.
        assertNull(TrackingParser.otpIn("Delivering to PIN code 560001"))
        assertNull(TrackingParser.otpIn("Delivering to pincode 560001"))
        // Parcels only take an explicit OTP.
        assertNull(TrackingParser.otpIn("Pin 560001", allowPin = false))
        assertEquals("3355", TrackingParser.otpIn("OTP 3355", allowPin = false))
    }

    @Test
    fun plates() {
        assertEquals("KA 01 AB 1234", TrackingParser.plateIn("White Swift KA01AB1234"))
        assertEquals("KA 05 HX 7781", TrackingParser.plateIn("Captain on KA-05-HX-7781"))
        assertEquals("DL 03 C 0042", TrackingParser.plateIn("DL 3C 0042"))
        assertEquals("MH 12 DE 1433", TrackingParser.plateIn("Auto MH12 DE 1433 arriving"))
        assertEquals("22 BH 1234 AA", TrackingParser.plateIn("Car 22 BH 1234 AA"))
        // Not a state code, or ordinary words and numbers.
        assertNull(TrackingParser.plateIn("XX 01 AB 1234"))
        assertNull(TrackingParser.plateIn("in 5 min"))
        assertNull(TrackingParser.plateIn("Order 12345"))
    }

    @Test
    fun minutes() {
        assertEquals(5, TrackingParser.minutesIn("Arriving in 5 min", bare = false))
        assertEquals(12, TrackingParser.minutesIn("will reach in about 12 minutes", bare = false))
        assertEquals(8, TrackingParser.minutesIn("Driver is 8 mins away", bare = false))
        assertEquals(30, TrackingParser.minutesIn("Arriving in 25-30 mins", bare = false))
        assertEquals(4, TrackingParser.minutesIn("ETA 4 min", bare = false))
        assertNull(TrackingParser.minutesIn("5 min", bare = false))
        assertEquals(5, TrackingParser.minutesIn("5 min", bare = true))
        assertEquals(65, TrackingParser.minutesIn("1 hr 5 min", bare = true))
        assertNull(TrackingParser.minutesIn("in 500 min", bare = false))
    }

    @Test
    fun clockTimes() {
        assertEquals(LocalTime.of(19, 45), TrackingParser.clockTimeIn("Arriving at 7:45 PM"))
        assertEquals(LocalTime.of(21, 0), TrackingParser.clockTimeIn("Arriving by 9 pm"))
        assertEquals(LocalTime.of(19, 40), TrackingParser.clockTimeIn("ETA 19:40"))
        assertNull(TrackingParser.clockTimeIn("Room 4 at noon"))
        // A ride arriving at a clock time gets that time today as its ETA.
        val ride = parse(input("com.olacabs.customer", "Cab confirmed", "Driver arriving at 6:20 PM"))!!
        assertEquals(LocalDateTime.of(2026, 11, 17, 18, 20).atZone(zone).toInstant().toEpochMilli(), ride.etaAt)
    }

    @Test
    fun progressValuesAreChecked() {
        assertNull(TrackProgress.of(5, 0, false))
        assertNull(TrackProgress.of(5, 10, true))
        assertNull(TrackProgress.of(-1, 10, false))
        val p = TrackProgress.of(15, 10, false, segments = listOf(5, 0, 5), points = listOf(-3, 5, 99))!!
        assertEquals(10, p.value)
        assertEquals(listOf(5, 5), p.segments)
        assertEquals(listOf(5), p.points)
        assertEquals(listOf(0.5f), p.segmentEnds)
        assertEquals(1f, p.fraction)
    }

    @Test
    fun textIsCleanedAndCapped() {
        assertEquals("Driver arriving", TrackingParser.clean("  Driver\u0007   arriving \n\n"))
        assertEquals(300, TrackingParser.clean("x".repeat(5000)).length)
        assertFalse(TrackingParser.clean("a\n\n\nb").contains("\n\n"))
        assertTrue(TrackingParser.isFinished("How was your ride?"))
    }
}
