package bosca.ecommerce.service

import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapReceipt

/**
 * Verifies an in-app-purchase receipt token against the issuing app store and reports whether the
 * purchase is valid (plus the store's canonical transaction id). The SPI behind a store integration,
 * selected by [key] (DI lookup, no `Class.forName`); each implementation handles one [platform].
 *
 * Mirrors the legacy `bosca.iap.IapVerifier` contract (a standalone receipt check — there is no cart or
 * subscription side effect here; granting an entitlement from a valid receipt is the caller's concern).
 *
 * **Replay protection is the consumer's responsibility.** A verifier is intentionally stateless
 * and idempotent: the same receipt/purchase token verifies to the same [IapReceipt] every time, with no
 * persistence. So the entitlement consumer (not yet built) MUST dedupe on the returned
 * [IapReceipt.transactionId] (per platform) — record it on first grant and reject a second grant for the
 * same id — or a captured-but-valid receipt could be redeemed repeatedly.
 *
 * Store credentials are passed in [credentials] (a string bag, the same secrets-config idiom the
 * payment/shipping providers use) so verifiers stay stateless and configuration-source-agnostic:
 *  - Apple: `password` (the app's shared secret, for auto-renewable subscriptions).
 *  - Google: `email` (service-account email) + `privateKey` (its PEM private key); `packageName` may
 *    also be supplied here when not passed explicitly.
 */
interface IapReceiptVerifier {
    /** DI key identifying this store verifier (e.g. `iap-apple`, `iap-google`). */
    val key: String

    /** The store this verifier handles. */
    val platform: IapPlatform

    /**
     * Verify [token] (the store-issued receipt/purchase token). [productId] and [packageName] identify
     * the Google product being checked (ignored by Apple, whose receipt is self-describing). [sandbox]
     * selects Apple's sandbox endpoint. Always returns a typed [IapReceipt] whose [IapReceipt.status]
     * distinguishes a genuine verdict (`VALID` / `INVALID` / `NOT_FOUND`) from `VERIFICATION_UNAVAILABLE`
     * (outage / bad credentials / network / unparseable) — the caller must not treat the latter as a
     * verdict.
     */
    suspend fun verify(
        token: String,
        productId: String? = null,
        packageName: String? = null,
        credentials: Map<String, String> = emptyMap(),
        sandbox: Boolean = false,
    ): IapReceipt
}
