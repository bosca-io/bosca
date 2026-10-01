package bosca.ecommerce.service

import bosca.ecommerce.model.IapRedeemInput
import bosca.ecommerce.model.IapRedemptionResult
import bosca.serialization.UUID

/**
 * Redeems a verified in-app-purchase receipt into a subscription entitlement, with replay-dedupe on the
 * store transaction id. The replay guard the stateless [IapReceiptVerifier] explicitly defers
 * to lives here: a captured-but-valid receipt redeemed twice grants only once.
 */
interface IapEntitlementService : bosca.service.Service {
    /** Verify an IAP receipt and, on VALID, grant/extend the buyer's entitlement exactly once.
     *  Dedupes on the store transaction id; a replayed receipt does NOT grant again. NOT_FOUND /
     *  INVALID / VERIFICATION_UNAVAILABLE never grant or record.
     *
     *  v1: the entitlement plan is taken from [IapRedeemInput.planId] (the verifier echoes the
     *  caller-supplied productId rather than deriving it independently, so a server-side
     *  productId->plan binding would imply a guarantee the store check does not provide). Admin-gated;
     *  buyer self-service is a follow-up once an account-owner evaluator exists. */
    suspend fun redeem(input: IapRedeemInput, principalId: UUID?): IapRedemptionResult
}
