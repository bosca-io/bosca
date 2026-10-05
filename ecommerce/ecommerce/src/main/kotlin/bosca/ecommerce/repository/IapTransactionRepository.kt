package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.IapPlatform
import bosca.ecommerce.model.IapTransaction
import bosca.serialization.UUID

/**
 * Persistence for `ecom.iap_transactions` — the IAP replay-dedupe ledger. [insertIfAbsent] atomically
 * claims a (platform, transaction id) the first time a valid receipt is redeemed; a unique index makes
 * a replayed receipt return null (already claimed). [platform] is a native enum column.
 */
@Repository
interface IapTransactionRepository {

    /** Claim a store transaction; returns the new row, or null when it was already claimed (replay). */
    @Query(
        "insert into ecom.iap_transactions (platform, transaction_id, account_id, plan_id, product_id) " +
            "values ((:platform)::ecom.iap_platform, :transactionId, :accountId, :planId, :productId) " +
            "on conflict (platform, transaction_id) do nothing returning *",
    )
    suspend fun insertIfAbsent(
        platform: IapPlatform,
        transactionId: String,
        accountId: UUID,
        planId: UUID,
        productId: String?,
    ): IapTransaction?

    /** Bind the granted entitlement subscription onto a claimed transaction row. */
    @Query("update ecom.iap_transactions set subscription_id = :subscriptionId where id = :id returning *")
    suspend fun setSubscription(id: UUID, subscriptionId: UUID): IapTransaction?

    /** The recorded claim for a store transaction, if any (used to report a replay's prior grant). */
    @Query("select * from ecom.iap_transactions where platform = (:platform)::ecom.iap_platform and transaction_id = :transactionId")
    suspend fun getByPlatformAndTransactionId(platform: IapPlatform, transactionId: String): IapTransaction?
}
