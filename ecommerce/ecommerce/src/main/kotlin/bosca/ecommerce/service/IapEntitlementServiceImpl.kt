package bosca.ecommerce.service

import bosca.db.transaction
import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapRedeemInput
import bosca.ecommerce.model.IapRedemptionResult
import bosca.ecommerce.model.IapRedemptionStatus
import bosca.ecommerce.model.IapVerificationStatus
import bosca.ecommerce.repository.IapTransactionRepository
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

/**
 * Redeems verified in-app-purchase receipts into entitlements, implementing the replay guard
 * the stateless [IapReceiptVerifier] explicitly defers to. A valid receipt is verified against
 * the issuing store, then the store transaction id is atomically claimed in `ecom.iap_transactions`; the
 * first claim grants/extends an external entitlement subscription, and a replay (a second receipt for the
 * same transaction) is rejected as [IapRedemptionStatus.ALREADY_REDEEMED] without granting again. A
 * non-VALID verdict (`INVALID` / `NOT_FOUND` / `VERIFICATION_UNAVAILABLE`) never grants or records.
 *
 * Store credentials come from `ecom.iap.*` application config (the same secrets-config idiom the payment
 * and tax providers use); the per-platform verifier is resolved by DI key (`iap-apple` / `iap-google`).
 *
 * Config is read from the injected [BoscaApplication] (the DI-registered app instance), NOT by
 * constructor-injecting `ApplicationConfig` directly — `ApplicationConfig` is not a DI-resolvable type,
 * so injecting it makes this provider throw `MissingProviderException` when the GraphQL dispatcher eagerly
 * builds `IapMutationController` at server boot. This mirrors `SecurityConfigurationImpl`.
 */
@ServiceImplementation
class IapEntitlementServiceImpl(
    private val subscriptionService: SubscriptionService,
    private val iapTransactionRepository: IapTransactionRepository,
    application: BoscaApplication,
) : IapEntitlementService {

    private val config: ApplicationConfig = application.environment.config

    override suspend fun redeem(input: IapRedeemInput, principalId: UUID?): IapRedemptionResult {
        // No verifier registered = the IAP module isn't installed; degrade to "couldn't reach a verdict"
        // rather than throw a raw DI error (mirrors the inventory connector's graceful skip).
        val verifier = verifier(input.platform)
            ?: return IapRedemptionResult(IapRedemptionStatus.VERIFICATION_UNAVAILABLE)
        val receipt = verifier.verify(
            input.token,
            input.productId,
            input.packageName,
            credentials(input.platform),
            sandbox(input.platform),
        )
        return when (receipt.status) {
            IapVerificationStatus.VALID -> grant(input, receipt, principalId)
            IapVerificationStatus.INVALID -> IapRedemptionResult(IapRedemptionStatus.INVALID, transactionId = receipt.transactionId)
            IapVerificationStatus.NOT_FOUND -> IapRedemptionResult(IapRedemptionStatus.NOT_FOUND)
            IapVerificationStatus.VERIFICATION_UNAVAILABLE -> IapRedemptionResult(IapRedemptionStatus.VERIFICATION_UNAVAILABLE)
        }
    }

    /** Claim the store transaction once, then grant/extend the entitlement; a replay returns the prior grant. */
    private suspend fun grant(
        input: IapRedeemInput,
        receipt: bosca.ecommerce.model.IapReceipt,
        principalId: UUID?,
    ): IapRedemptionResult = transaction {
        val txId = receipt.transactionId ?: error("VALID IAP receipt without a transaction id")
        val claim = iapTransactionRepository.insertIfAbsent(input.platform, txId, input.accountId, input.planId, receipt.productId)
        if (claim == null) {
            // The (platform, transaction id) was already claimed — a replay grants nothing.
            val existing = iapTransactionRepository.getByPlatformAndTransactionId(input.platform, txId)
            return@transaction IapRedemptionResult(IapRedemptionStatus.ALREADY_REDEEMED, existing?.subscriptionId, txId)
        }
        val subscription = subscriptionService.grantExternal(input.storeId, input.accountId, input.planId, principalId)
        iapTransactionRepository.setSubscription(claim.id, subscription.id)
        IapRedemptionResult(IapRedemptionStatus.GRANTED, subscription.id, txId)
    }

    /** Resolve the per-platform receipt verifier by its DI key, or null when the `iap` provider module isn't installed. */
    private suspend fun verifier(platform: IapPlatform): IapReceiptVerifier? =
        try {
            provide(name = platform.verifierKey)
        } catch (e: MissingProviderException) {
            log.warn("no IAP verifier '{}' registered; the iap provider module is not installed ({})", platform.verifierKey, e.message)
            null
        }

    /** Store credentials for [platform], read from `ecom.iap.*` application config. */
    private fun credentials(platform: IapPlatform): Map<String, String> = when (platform) {
        IapPlatform.IOS -> mapOf("password" to config.value("ecom.iap.apple.password"))
        IapPlatform.ANDROID -> mapOf(
            "email" to config.value("ecom.iap.google.email"),
            "privateKey" to config.value("ecom.iap.google.privateKey"),
            "packageName" to config.value("ecom.iap.google.packageName"),
        )
    }

    /** Apple has a separate sandbox endpoint (selected by config); Google verifies against one endpoint. */
    private fun sandbox(platform: IapPlatform): Boolean = when (platform) {
        IapPlatform.IOS -> config.value("ecom.iap.apple.sandbox").toBoolean()
        IapPlatform.ANDROID -> false
    }

    private val IapPlatform.verifierKey: String
        get() = when (this) {
            IapPlatform.IOS -> "iap-apple"
            IapPlatform.ANDROID -> "iap-google"
        }

    private fun ApplicationConfig.value(path: String): String = propertyOrNull(path)?.getString().orEmpty()

    private companion object {
        private val log = LoggerFactory.getLogger(IapEntitlementServiceImpl::class.java)
    }
}
