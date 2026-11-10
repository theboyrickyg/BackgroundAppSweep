package com.rickyboy.appsweep

import android.app.Activity
import android.content.Context
import android.os.Build
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

/**
 * App Closer Unlimited: a one-off Google Play purchase.
 *
 * Free: the first [FREE_CLEANS] Close All cleans do everything. After that the RAM gauge, the list
 * and closing apps one at a time still work, but Close All, Quick Sweep and auto-start blocking
 * need Unlimited.
 */
object Unlock {
    const val FREE_CLEANS = 5
    /** The in-app product ID. Create a one-time product with exactly this ID in Play Console. */
    const val PRODUCT_ID = "app_closer_unlimited"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("unlock", Context.MODE_PRIVATE)

    // ---------- Free cleans + lifetime totals ----------

    fun isUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean("bought", false) || prefs(ctx).getBoolean("owner", false)

    fun cleansUsed(ctx: Context): Int = prefs(ctx).getInt("cleans_used", 0)
    fun freeCleansLeft(ctx: Context): Int = (FREE_CLEANS - cleansUsed(ctx)).coerceAtLeast(0)
    fun canClean(ctx: Context): Boolean = isUnlocked(ctx) || freeCleansLeft(ctx) > 0

    /** Call when a Close All clean starts. */
    fun useClean(ctx: Context) {
        if (isUnlocked(ctx)) return
        prefs(ctx).edit().putInt("cleans_used", cleansUsed(ctx) + 1).apply()
    }

    fun addTotals(ctx: Context, appsClosed: Int, cacheBytes: Long) {
        val p = prefs(ctx)
        p.edit()
            .putInt("total_closed", p.getInt("total_closed", 0) + appsClosed.coerceAtLeast(0))
            .putLong("total_cache", p.getLong("total_cache", 0) + cacheBytes.coerceAtLeast(0))
            .apply()
    }

    fun totalClosed(ctx: Context): Int = prefs(ctx).getInt("total_closed", 0)
    fun totalCache(ctx: Context): Long = prefs(ctx).getLong("total_cache", 0)

    // ---------- Owner unlock (copies not installed from the Play Store only) ----------

    fun installedFromPlay(ctx: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }
        installer == "com.android.vending"
    } catch (_: Exception) {
        false
    }

    /** Returns the new state, or null if not allowed (Play Store copies). */
    fun toggleOwnerUnlock(ctx: Context): Boolean? {
        if (installedFromPlay(ctx)) return null
        val now = !prefs(ctx).getBoolean("owner", false)
        prefs(ctx).edit().putBoolean("owner", now).apply()
        return now
    }

    // ---------- Google Play Billing ----------

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    /** Called on the main thread when the purchase goes through (or a past purchase is found). */
    var onUnlocked: (() -> Unit)? = null

    /** The price as Play shows it, e.g. "£2.99", once known. */
    fun price(): String? = details?.oneTimePurchaseOfferDetails?.formattedPrice

    private fun handle(ctx: Context, purchases: List<Purchase>?) {
        val mine = purchases.orEmpty().filter {
            PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (mine.isEmpty()) return
        val was = isUnlocked(ctx)
        prefs(ctx).edit().putBoolean("bought", true).apply()
        mine.filter { !it.isAcknowledged }.forEach { p ->
            // Unacknowledged purchases are refunded by Google after 3 days
            client?.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
            ) { }
        }
        if (!was) android.os.Handler(android.os.Looper.getMainLooper()).post { onUnlocked?.invoke() }
    }

    /** Connects to Play, restores any past purchase and fetches the price. Safe to call often. */
    fun connect(ctx: Context, then: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val c = client ?: BillingClient.newBuilder(app)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(app, purchases)
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
            .also { client = it }
        if (c.isReady) {
            refresh(app, then)
            return
        }
        try {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh(app, then)
                    else then?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
                }

                override fun onBillingServiceDisconnected() {}
            })
        } catch (_: Exception) {
            then?.invoke()
        }
    }

    private fun refresh(ctx: Context, then: (() -> Unit)?) {
        val c = client ?: return
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(ctx, purchases)
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { _, list ->
            details = list.firstOrNull()
            then?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
    }

    /** Opens Google Play's purchase sheet. Returns an error message if it can't. */
    fun buy(activity: Activity): String? {
        val c = client
        val d = details
        if (c == null || !c.isReady || d == null) {
            return if (installedFromPlay(activity)) "Google Play isn't responding. Check your connection and try again."
            else "Buying works once App Closer is installed from the Play Store."
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())
            )
            .build()
        val r = c.launchBillingFlow(activity, params)
        return if (r.responseCode == BillingClient.BillingResponseCode.OK ||
            r.responseCode == BillingClient.BillingResponseCode.USER_CANCELED
        ) null else "Couldn't open Google Play (${r.debugMessage.ifBlank { "code ${r.responseCode}" }})."
    }
}
