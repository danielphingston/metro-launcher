package io.github.danielphingston.metro.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryPurchasesAsync
import io.github.danielphingston.metro.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Metro Plus one-time purchase (quick settings tiles, assistant, smart search; the Play product keeps its
 * original id, never change it). Unlocked when Play says it is owned, or always in
 * debug builds.
 *
 * The last known owned state is cached so the pack works offline and before billing connects;
 * it is re-checked with Play each time billing connects. The product may not exist in Play
 * Console yet: then the price stays null and nothing else happens.
 */
class Purchases(
    context: Context,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences("purchases", Context.MODE_PRIVATE)

    private var owned = prefs.getBoolean(KEY_OWNED, false)
    private val _unlocked = MutableStateFlow(computeUnlocked())
    private val _price = MutableStateFlow<String?>(null)

    val quickTilesUnlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Play's formatted price, or null while unknown or when the product isn't available. */
    val quickTilesPrice: StateFlow<String?> = _price.asStateFlow()

    private var details: ProductDetails? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var reconnect: Job? = null

    /** True from startConnection until Play answers or drops us, so connection attempts never pile up. */
    private var connecting = false
    private val throttle = RecheckThrottle(clock)

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            // Fires after the purchase flow ends; purchases is null on cancel or error.
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                scope.launch { handle(purchases, authoritative = false) }
            } else if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                // Our cache missed an earlier purchase (e.g. a new device): ask Play for the truth.
                scope.launch { refresh() }
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        connect()
    }

    /** Opens Play's purchase sheet. Does nothing while the product isn't available. */
    fun buyQuickTiles(activity: Activity) {
        val product = details ?: return
        if (!client.isReady) return
        // The sheet covers the launcher: when it closes, onResume must re-read what Play says.
        throttle.forceNext()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    /**
     * Asks Play again, e.g. when the launcher comes back to the front: a pending purchase may have
     * completed meanwhile, and an acknowledgement that failed gets another try. Throttled to once
     * per [RecheckThrottle.INTERVAL_MS] so resuming the launcher doesn't hit Play every time,
     * except right after a purchase flow, which is always re-read.
     */
    fun recheck() {
        if (!throttle.shouldRun()) return
        if (client.isReady) {
            scope.launch { refresh() }
        } else {
            connect()
        }
    }

    private fun computeUnlocked() = owned || BuildConfig.DEBUG

    private fun connect() {
        // A pending reconnect or an attempt in flight will deliver the refresh; don't stack another.
        if (client.isReady || connecting || reconnect?.isActive == true) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    backoffMs = INITIAL_BACKOFF_MS
                    throttle.markRan()
                    scope.launch { refresh() }
                } else {
                    // Billing unavailable here (no Play Store, unsupported device...): try again later.
                    scheduleReconnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnect?.isActive == true) return
        reconnect = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            reconnect = null
            connect()
        }
    }

    /** Re-reads what Play says is owned and the product's price. */
    private suspend fun refresh() {
        try {
            val result = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            )
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handle(result.purchasesList, authoritative = true)
            }
            loadDetails()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Billing is optional: the cached state stays and the next connection tries again.
        }
    }

    private suspend fun loadDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val found = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, queried ->
                cont.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queried.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                    } else {
                        null
                    },
                )
            }
        }
        details = found
        _price.value = found?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    /**
     * Applies [purchases]. Only PURCHASED counts (PENDING doesn't unlock yet) and each one is
     * acknowledged, or Play refunds it after 3 days. A full query ([authoritative]) can also
     * take the pack away again, e.g. after a refund.
     */
    private suspend fun handle(purchases: List<Purchase>, authoritative: Boolean) {
        val mine = purchases.filter { PRODUCT_ID in it.products }
        val bought = mine.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (bought.isNotEmpty() || authoritative) setOwned(bought.isNotEmpty())
        bought.filterNot { it.isAcknowledged }.forEach { purchase ->
            try {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Retried by the next refresh (every resume), well inside the 3 days.
            }
        }
    }

    private fun setOwned(value: Boolean) {
        owned = value
        prefs.edit().putBoolean(KEY_OWNED, value).apply()
        _unlocked.value = computeUnlocked()
    }

    private companion object {
        const val PRODUCT_ID = "tiles_quick_settings"
        const val KEY_OWNED = "quick_tiles_owned"
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}

/**
 * Decides when [Purchases.recheck] may actually talk to Play: at most once per [INTERVAL_MS]
 * unless [forceNext] was called. Separate from [Purchases] so it can be tested with a fake clock.
 */
class RecheckThrottle(private val clock: () -> Long, private val intervalMs: Long = INTERVAL_MS) {
    private var lastRun: Long? = null
    private var forced = false

    /** True when a check is due; records it as run. */
    fun shouldRun(): Boolean {
        val now = clock()
        val last = lastRun
        if (!forced && last != null && now - last in 0 until intervalMs) return false
        forced = false
        lastRun = now
        return true
    }

    /** Notes that a check just happened by other means (e.g. a fresh connection). */
    fun markRan() {
        lastRun = clock()
    }

    /** The next [shouldRun] is true regardless of the interval, e.g. after a purchase flow. */
    fun forceNext() {
        forced = true
    }

    companion object {
        const val INTERVAL_MS = 10 * 60_000L
    }
}
