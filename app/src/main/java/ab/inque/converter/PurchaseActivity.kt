package ab.inque.converter

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ab.inque.converter.databinding.ActivityPurchaseBinding
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

class PurchaseActivity : AppCompatActivity() {
    companion object {
        private const val PREMIUM_PRODUCT_ID = "premium_upgrade"
        private const val TAG = "PurchaseActivity"
    }

    private lateinit var binding: ActivityPurchaseBinding
    private lateinit var billingClient: BillingClient
    private var premiumProductDetails: ProductDetails? = null
    private var premiumProductType: String? = null
    private var premiumOfferToken: String? = null
    private var billingReady: Boolean = false
    private var billingConnecting: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPurchaseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buyButton.setOnClickListener {
            launchPremiumPurchaseFlow()
        }

        binding.unlockWithUriButton.setOnClickListener {
            val uri = binding.unlockUriInput.text?.toString()?.trim()
            if (PremiumUnlockUri.isValidUnlockUri(uri)) {
                ConversionPrefs.setUnlocked(this, true)
                showToast(getString(R.string.toast_purchase_success))
                finish()
            } else {
                showToast(getString(R.string.toast_unlock_uri_invalid))
            }
        }

        binding.buyButton.isEnabled = true
        updateStatus("Connecting to Google Play...")
        setupBilling()
    }

    private fun setupBilling() {
        if (billingReady || billingConnecting) return

        val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
            Log.d(TAG, "purchasesUpdated response=${billingResult.responseCode} count=${purchases?.size ?: 0}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                purchases.forEach { handlePurchase(it) }
            } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                updateStatus("Purchase failed (code ${billingResult.responseCode})")
                showToast(getString(R.string.error_purchase_failed_with_code, billingResult.responseCode))
            }
        }

        if (!::billingClient.isInitialized) {
            billingClient = BillingClient.newBuilder(this)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .enableAutoServiceReconnection()
                .build()
        }

        billingConnecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                billingConnecting = false
                Log.d(TAG, "billingSetupFinished response=${billingResult.responseCode}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    billingReady = true
                    updateStatus("Connected. Loading product details...")
                    queryProductDetails()
                    restoreExistingPurchases()
                } else {
                    billingReady = false
                    updateStatus("Play Billing unavailable (code ${billingResult.responseCode})")
                    showToast(getString(R.string.error_purchase_unavailable_with_code, billingResult.responseCode))
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "billingServiceDisconnected")
                billingReady = false
                billingConnecting = false
                updateStatus("Disconnected from Google Play. Retrying...")
            }
        })
    }

    private fun queryProductDetails() {
        updateStatus("Checking product as INAPP...")
        queryProductDetailsForType(BillingClient.ProductType.INAPP) { inappResult, inappProducts ->
            Log.d(TAG, "query INAPP response=${inappResult.responseCode} count=${inappProducts.size}")
            if (inappResult.responseCode == BillingClient.BillingResponseCode.OK && inappProducts.isNotEmpty()) {
                val details = inappProducts.first()
                premiumProductDetails = details
                premiumProductType = BillingClient.ProductType.INAPP
                premiumOfferToken = offerTokenFor(details, BillingClient.ProductType.INAPP)
                updateStatus("Product loaded: $PREMIUM_PRODUCT_ID (one-time)")
                return@queryProductDetailsForType
            }

            updateStatus("INAPP not found. Checking as subscription...")
            queryProductDetailsForType(BillingClient.ProductType.SUBS) { subsResult, subsProducts ->
                Log.d(TAG, "query SUBS response=${subsResult.responseCode} count=${subsProducts.size}")
                if (subsResult.responseCode == BillingClient.BillingResponseCode.OK && subsProducts.isNotEmpty()) {
                    val details = subsProducts.first()
                    val offerToken = offerTokenFor(details, BillingClient.ProductType.SUBS)

                    premiumProductDetails = details
                    premiumProductType = BillingClient.ProductType.SUBS
                    premiumOfferToken = offerToken

                    if (offerToken == null) {
                        updateStatus("Subscription found but no offer token.")
                        showToast(getString(R.string.error_purchase_no_offer))
                    } else {
                        updateStatus("Product loaded: $PREMIUM_PRODUCT_ID (subscription)")
                    }
                } else {
                    premiumProductDetails = null
                    premiumProductType = null
                    premiumOfferToken = null

                    val code = when {
                        subsResult.responseCode != BillingClient.BillingResponseCode.OK -> subsResult.responseCode
                        else -> inappResult.responseCode
                    }
                    updateStatus("Product not found in Play (code $code).")
                    showToast(getString(R.string.error_purchase_product_not_found, PREMIUM_PRODUCT_ID, code))
                }
            }
        }
    }

    private fun queryProductDetailsForType(
        productType: String,
        onResult: (BillingResult, List<ProductDetails>) -> Unit
    ) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PREMIUM_PRODUCT_ID)
            .setProductType(productType)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            onResult(billingResult, queryResult.productDetailsList)
        }
    }

    private fun offerTokenFor(details: ProductDetails, productType: String): String? {
        return if (productType == BillingClient.ProductType.SUBS) {
            details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        } else {
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        }
    }

    private fun launchPremiumPurchaseFlow() {
        if (!billingReady) {
            updateStatus("Billing not ready. Reconnecting...")
            showToast(getString(R.string.error_purchase_unavailable))
            setupBilling()
            return
        }

        val details = premiumProductDetails ?: run {
            // Retry details query in case Play just connected; provide clear feedback instead of silent no-op.
            queryProductDetails()
            updateStatus("Still loading product details. Tap Buy again.")
            showToast(getString(R.string.error_purchase_loading))
            return
        }

        val token = premiumOfferToken
        if (token.isNullOrBlank()) {
            showToast(getString(R.string.error_purchase_no_offer))
            updateStatus("Product has no purchasable offer.")
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(token)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        updateStatus("Opening Google Play purchase dialog...")
        val launchResult = billingClient.launchBillingFlow(this, billingFlowParams)
        Log.d(TAG, "launchBillingFlow response=${launchResult.responseCode}")
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            updateStatus("Could not open purchase dialog (code ${launchResult.responseCode}).")
            showToast(getString(R.string.error_purchase_failed_with_code, launchResult.responseCode))
        }
    }

    private fun restoreExistingPurchases() {
        val inappParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(inappParams) { billingResult, purchases ->
            Log.d(TAG, "restore INAPP response=${billingResult.responseCode} count=${purchases.size}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { handlePurchase(it) }
            }
        }

        val subsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(subsParams) { billingResult, purchases ->
            Log.d(TAG, "restore SUBS response=${billingResult.responseCode} count=${purchases.size}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { handlePurchase(it) }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(PREMIUM_PRODUCT_ID)) return

        if (purchase.isAcknowledged) {
            unlockPremiumAndFinish()
            return
        }

        val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(acknowledgeParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                unlockPremiumAndFinish()
            } else {
                showToast(getString(R.string.error_purchase_failed_with_code, billingResult.responseCode))
            }
        }
    }

    private fun unlockPremiumAndFinish() {
        ConversionPrefs.setUnlocked(this, true)
        updateStatus("Purchase successful.")
        showToast(getString(R.string.toast_purchase_success))
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(message: String) {
        binding.purchaseStatus.text = message
        Log.d(TAG, message)
    }

    override fun onDestroy() {
        if (::billingClient.isInitialized) billingClient.endConnection()
        super.onDestroy()
    }
}
