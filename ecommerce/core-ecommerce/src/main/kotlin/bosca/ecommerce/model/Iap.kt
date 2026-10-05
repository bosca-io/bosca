package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/** The app store a purchase receipt comes from. `ecom` does not persist this (verification is transient). */
@Serializable
enum class IapPlatform { ANDROID, IOS }

/**
 * The verdict of an in-app-purchase receipt check. The entitlement gate must be able to tell a genuine
 * "the store says this is not a valid purchase" from "we could not reach a verdict" — collapsing both
 * into `false`/`null` either denies paying customers during an outage or risks granting on unverified
 * tokens.
 */
@Serializable
enum class IapVerificationStatus {
    /** The store confirmed the purchase (Apple `status == 0`; Google `purchaseState == 0`). */
    VALID,

    /** The store reached a verdict and the receipt is not a valid purchase (tampered/expired/canceled/unauthenticated). */
    INVALID,

    /** The store has no record of this purchase token (e.g. Google Play `404`). */
    NOT_FOUND,

    /**
     * Verification could not be completed — outage, bad service-account credentials, network/timeout, or an
     * unparseable response. **Not** a verdict: the caller must neither grant nor treat it as fraud.
     */
    VERIFICATION_UNAVAILABLE,
}

/**
 * The outcome of verifying an in-app-purchase receipt with the store (Apple App Store / Google Play),
 * returned by an [bosca.ecommerce.service.IapReceiptVerifier]. [transactionId] is the store's canonical
 * id for the purchase (Apple `download_id`; Google `orderId`) — present whenever the store returned one
 * (always on [IapVerificationStatus.VALID]). [status] is the typed verdict. Verification is read-only —
 * nothing is persisted here; granting an entitlement is the caller's job.
 */
@Serializable
data class IapReceipt(
    val platform: IapPlatform,
    val status: IapVerificationStatus,
    val transactionId: String? = null,
    val productId: String? = null,
    /** Apple only: `Sandbox` or `Production`; null for Google. */
    val environment: String? = null,
) {
    /** Convenience: the purchase is confirmed only on [IapVerificationStatus.VALID]. */
    val valid: Boolean get() = status == IapVerificationStatus.VALID
}
