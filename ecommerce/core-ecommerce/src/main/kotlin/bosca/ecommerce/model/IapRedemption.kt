package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The outcome of redeeming an in-app-purchase receipt into an entitlement. Distinct from the store's
 * verification verdict ([IapVerificationStatus]): it adds the dedupe outcome [ALREADY_REDEEMED] (the
 * store transaction was already claimed — a replay) and the success outcome [GRANTED] (the entitlement
 * was granted/extended exactly once). [INVALID] / [NOT_FOUND] / [VERIFICATION_UNAVAILABLE] are passed
 * through from the verifier and never grant or record.
 */
@Serializable
enum class IapRedemptionStatus { GRANTED, ALREADY_REDEEMED, INVALID, NOT_FOUND, VERIFICATION_UNAVAILABLE }

/**
 * A verified in-app purchase to redeem into a subscription entitlement. [token] is the store-issued
 * receipt / purchase token verified against the app store; [planId] is the entitlement plan to grant.
 * [productId] is required for Google (and ignored by Apple, whose receipt is self-describing);
 * [packageName] is the Android app package name (Google only).
 */
@Serializable
data class IapRedeemInput(
    @Contextual
    val storeId: UUID,
    @Contextual
    val accountId: UUID,
    @Contextual
    val planId: UUID,
    val platform: IapPlatform,
    val token: String,
    val productId: String? = null,
    val packageName: String? = null,
)

/**
 * The result of redeeming an in-app-purchase receipt. [subscriptionId] is the granted/extended
 * entitlement subscription (present on [IapRedemptionStatus.GRANTED], and on
 * [IapRedemptionStatus.ALREADY_REDEEMED] when the prior claim recorded one). [transactionId] is the
 * store transaction id that was redeemed, when available.
 */
@Serializable
data class IapRedemptionResult(
    val status: IapRedemptionStatus,
    @Contextual
    val subscriptionId: UUID? = null,
    val transactionId: String? = null,
)
