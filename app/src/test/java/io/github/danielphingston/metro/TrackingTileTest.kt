package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.live.tracking.ParcelEntry
import io.github.danielphingston.metro.live.tracking.ParcelLog
import io.github.danielphingston.metro.live.tracking.ParcelStage
import io.github.danielphingston.metro.live.tracking.ParcelStore
import io.github.danielphingston.metro.live.tracking.TrackItem
import io.github.danielphingston.metro.live.tracking.TrackKind
import io.github.danielphingston.metro.ui.tiles.TrackingModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** The tracking tile's kept parcels, its model and its place in the saved layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackingTileTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 17, 18, 0).atZone(zone).toInstant().toEpochMilli()
    private val midnight = LocalDateTime.of(2026, 11, 18, 0, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parcel(
        key: String,
        stage: ParcelStage,
        title: String = "Out for delivery",
        detail: String = "boAt Airdopes 141",
        pkg: String = "in.amazon.mShop.android.shopping",
        postTime: Long = now - 60_000,
        secret: Boolean = false,
    ) = TrackItem(key, pkg, "$pkg/0", TrackKind.PARCEL, title, detail, postTime, stage = stage, secret = secret)

    private fun ride(key: String, eta: Long?, postTime: Long = now - 60_000, otp: String? = "4821", secret: Boolean = false) =
        TrackItem(key, "com.ubercab", "com.ubercab/0", TrackKind.RIDE, "Driver arriving", "White Swift", postTime, etaAt = eta, otp = otp, plate = "KA 01 AB 1234", secret = secret)

    // ---- parcel log ----

    @Test
    fun parcelsAreKeptUntilMidnight() {
        val kept = ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.ARRIVING_TODAY)), now, keepText = true, zone = zone)
        assertEquals(1, kept.size)
        assertEquals(midnight, kept[0].until)
        assertEquals(now, kept[0].caughtAt)
        assertEquals(1, ParcelLog.live(kept, midnight - 1).size)
        assertTrue(ParcelLog.live(kept, midnight).isEmpty())
    }

    @Test
    fun aStageNeverGoesBackAndTheFirstCatchStays() {
        val first = ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.OUT_FOR_DELIVERY)), now, true, zone)
        val later = ParcelLog.update(first, listOf(parcel("a", ParcelStage.ARRIVING_TODAY, title = "Arriving today")), now + 60_000, true, zone)
        assertEquals(ParcelStage.OUT_FOR_DELIVERY, later.single().stage)
        assertEquals("Arriving today", later.single().title)
        assertEquals(now, later.single().caughtAt)
    }

    @Test
    fun deliveredRemovesTheParcelItIsAbout() {
        val two = ParcelLog.update(
            emptyList(),
            listOf(
                parcel("a", ParcelStage.OUT_FOR_DELIVERY, detail = "boAt Airdopes 141"),
                parcel("b", ParcelStage.ARRIVING_TODAY, title = "Arriving today", detail = "Phone case purple"),
            ),
            now, true, zone,
        )
        // Same notification updated to "delivered".
        assertEquals(listOf("b"), ParcelLog.update(two, listOf(parcel("a", ParcelStage.DELIVERED, title = "Delivered")), now, true, zone).map { it.key })
        // Another notification that names the item.
        val named = parcel("c", ParcelStage.DELIVERED, title = "Delivered", detail = "Phone case purple was handed to resident")
        assertEquals(listOf("a"), ParcelLog.update(two, listOf(named), now, true, zone).map { it.key })
        // One that says nothing about which: only the app's only parcel goes.
        val vague = parcel("d", ParcelStage.DELIVERED, title = "Delivered", detail = "")
        assertEquals(2, ParcelLog.update(two, listOf(vague), now, true, zone).size)
        val one = ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.OUT_FOR_DELIVERY)), now, true, zone)
        assertTrue(ParcelLog.update(one, listOf(vague), now, true, zone).isEmpty())
        // Another app's delivery leaves Amazon's parcels alone.
        assertEquals(2, ParcelLog.update(two, listOf(vague.copy(packageName = "com.flipkart.android")), now, true, zone).size)
    }

    @Test
    fun yesterdaysArrivingTodayIsNotCaughtAgain() {
        val yesterday = now - 24 * 3_600_000L
        assertTrue(ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.ARRIVING_TODAY, postTime = yesterday)), now, true, zone).isEmpty())
    }

    @Test
    fun withoutContentOnlyTheAppAndStageAreKept() {
        val off = ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.OUT_FOR_DELIVERY)), now, keepText = false, zone = zone).single()
        assertEquals("", off.title)
        assertEquals("", off.detail)
        val secret = ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.OUT_FOR_DELIVERY, secret = true)), now, keepText = true, zone = zone).single()
        assertEquals("", secret.title)
    }

    @Test
    fun theLogIsCapped() {
        val many = (1..40).map { parcel("k$it", ParcelStage.ARRIVING_TODAY) }
        assertEquals(ParcelLog.MAX_ENTRIES, ParcelLog.update(emptyList(), many, now, true, zone).size)
    }

    @Test
    fun storedParcelsRoundTripAndDamageIsSkipped() {
        val entries = ParcelLog.update(emptyList(), listOf(parcel("a", ParcelStage.OUT_FOR_DELIVERY)), now, true, zone)
        assertEquals(entries, ParcelLog.decode(ParcelLog.encode(entries)))
        assertTrue(ParcelLog.decode(null).isEmpty())
        assertTrue(ParcelLog.decode("not json").isEmpty())
        assertTrue(ParcelLog.decode("{\"a\":1}").isEmpty())
        // Bad entries go, good ones stay.
        val mixed = JSONArray(ParcelLog.encode(entries))
            .put(org.json.JSONObject().put("key", "x"))
            .put("junk")
            .put(org.json.JSONObject(ParcelLog.encode(entries).let { JSONArray(it).getJSONObject(0).toString() }).put("stage", "warp"))
            .put(org.json.JSONObject(JSONArray(ParcelLog.encode(entries)).getJSONObject(0).toString()).put("until", "soon"))
        assertEquals(entries, ParcelLog.decode(mixed.toString()))
    }

    @Test
    fun theStoreKeepsParcelsInItsOwnPreferencesFile() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = ParcelStore(context, scope, Dispatchers.Unconfined)
        val today = System.currentTimeMillis()
        store.update(listOf(parcel("a", ParcelStage.OUT_FOR_DELIVERY, postTime = today)), today, keepText = true)
        assertEquals(1, store.entries.value.size)
        // A new process reads it back.
        assertEquals(1, ParcelStore(context, scope, Dispatchers.Unconfined).entries.value.size)
        store.forgetText()
        assertEquals("", store.entries.value.single().title)
        store.clear()
        assertTrue(ParcelStore(context, scope, Dispatchers.Unconfined).entries.value.isEmpty())
        // Damaged data never crashes the store.
        context.getSharedPreferences("tracking", 0).edit().putString("parcels", "[{\"key\":").commit()
        assertTrue(ParcelStore(context, scope, Dispatchers.Unconfined).entries.value.isEmpty())
        context.getSharedPreferences("tracking", 0).edit().putInt("parcels", 3).commit()
        assertTrue(ParcelStore(context, scope, Dispatchers.Unconfined).entries.value.isEmpty())
    }

    // ---- model ----

    private fun entry(key: String, stage: ParcelStage, caughtAt: Long = now) =
        ParcelEntry(key, "com.flipkart.android", "com.flipkart.android/0", stage, "Phone case", "", caughtAt, midnight)

    @Test
    fun liveTripsComeFirstSoonestFirstThenParcels() {
        val model = TrackingModel.of(
            listOf(ride("late", now + 20 * 60_000), ride("none", null), ride("soon", now + 3 * 60_000)),
            listOf(entry("today", ParcelStage.ARRIVING_TODAY), entry("out", ParcelStage.OUT_FOR_DELIVERY, caughtAt = now - 1)),
            accessGranted = true, showContent = true, now = now,
        )
        assertEquals(TrackingModel.State.ACTIVE, model.state)
        assertEquals(listOf("soon", "late", "none", "out", "today"), model.rows.map { it.key })
        assertTrue(model.ticks)
    }

    @Test
    fun theTileStatesWithoutAccessOrAnything() {
        assertEquals(TrackingModel.State.NO_ACCESS, TrackingModel.of(listOf(ride("a", null)), emptyList(), false, true, now).state)
        val empty = TrackingModel.of(emptyList(), listOf(entry("old", ParcelStage.ARRIVING_TODAY).copy(until = now)), true, true, now)
        assertEquals(TrackingModel.State.EMPTY, empty.state)
        assertFalse(empty.ticks)
        // Parcels alone never tick.
        assertFalse(TrackingModel.of(emptyList(), listOf(entry("a", ParcelStage.ARRIVING_TODAY)), true, true, now).ticks)
    }

    @Test
    fun aLockedTileShowsNothingItCaught() {
        assertEquals(TrackingModel.State.LOCKED, TrackingModel.LOCKED.state)
        assertTrue(TrackingModel.LOCKED.rows.isEmpty())
        assertFalse(TrackingModel.LOCKED.ticks)
        assertEquals("tracking, unlock metro plus to use", TrackingModel.LOCKED.description { "x" })
    }

    @Test
    fun contentOffHidesWordsCodesAndPictures() {
        val on = TrackingModel.of(listOf(ride("a", now + 60_000)), emptyList(), true, showContent = true, now = now).rows.single()
        assertEquals("Driver arriving", on.status)
        assertEquals("4821", on.otp)
        assertEquals("KA 01 AB 1234", on.plate)
        assertTrue(on.showPicture)
        val off = TrackingModel.of(listOf(ride("a", now + 60_000)), listOf(entry("p", ParcelStage.OUT_FOR_DELIVERY)), true, showContent = false, now = now)
        val r = off.rows[0]
        assertEquals("ride on the way", r.status)
        assertEquals("", r.detail)
        assertNull(r.otp)
        assertNull(r.plate)
        assertFalse(r.showPicture)
        // The ETA and progress still show.
        assertEquals(now + 60_000, r.etaAt)
        assertEquals("out for delivery", off.rows[1].status)
    }

    @Test
    fun aSecretNotificationKeepsItsCodeBehindATapButNothingElse() {
        val r = TrackingModel.of(listOf(ride("a", null, secret = true)), emptyList(), true, true, now).rows.single()
        assertEquals("ride on the way", r.status)
        assertEquals("", r.detail)
        assertNull(r.plate)
        assertFalse(r.showPicture)
        assertEquals("4821", r.otp)
    }

    @Test
    fun etaText() {
        assertEquals(4, TrackingModel.minutesLeft(now + 3 * 60_000 + 1, now))
        assertEquals(0, TrackingModel.minutesLeft(now - 1, now))
        assertNull(TrackingModel.minutesLeft(null, now))
        assertEquals("8m", TrackingModel.shortEta(8))
        assertEquals("now", TrackingModel.shortEta(0))
        assertEquals("1h", TrackingModel.shortEta(75))
        assertEquals("12" to "min", TrackingModel.bigEta(12))
        assertEquals("1:05" to "h", TrackingModel.bigEta(65))
        assertEquals("now" to "", TrackingModel.bigEta(0))
        // A stale ETA is not shown.
        assertNull(TrackingModel.of(listOf(ride("a", now - 30 * 60_000)), emptyList(), true, true, now).rows.single().etaAt)
        val spoken = TrackingModel.of(listOf(ride("a", now + 4 * 60_000)), emptyList(), true, true, now).description { "Uber" }
        assertEquals("tracking, Uber Driver arriving, 4 minutes", spoken)
    }

    // ---- layout and backups ----

    @Test
    fun theTileIsSavedAsTypeTracking() {
        val tiles = listOf(TileSpec(9, TileSize.WIDE, TileContent.Tracking, 0, 0))
        val json = TileJson.encode(tiles)
        assertEquals("tracking", json.getJSONObject(0).getString("type"))
        assertEquals(tiles, TileJson.decode(json))
    }

    @Test
    fun aBackupWithATrackingTileRestoresIt() {
        val tiles = TileJson.encode(listOf(TileSpec(9, TileSize.WIDE, TileContent.Tracking, 0, 0))).toString()
        val prefs = preferencesOf(stringPreferencesKey("tiles") to tiles)
        val restored = Backup.decode(Backup.encode(prefs, "test", 0L))
        assertEquals(TileContent.Tracking, TileJson.read(restored[stringPreferencesKey("tiles")]!!)!!.tiles.single().content)
        // Its parcels are not in the settings, so a backup never carries them.
        assertFalse(Backup.encode(prefs, "test", 0L).contains("parcels"))
    }
}
