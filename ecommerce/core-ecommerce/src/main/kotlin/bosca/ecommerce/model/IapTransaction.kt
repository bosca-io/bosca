package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A redeemed in-app-purchase store transaction (`ecom.iap_transactions`) — the replay-dedupe ledger.
 * One row is claimed per (platform, store [transactionId]) the first time a valid receipt is redeemed;
 * a unique index on that pair makes a replayed receipt a no-op (it never grants a second entitlement).
 * [subscriptionId] is set once the entitlement subscription is granted/extended;
 * [planId] is the entitlement plan the redemption was claimed against; [productId] echoes the store
 * product id from the verified receipt.
 */
@Serializable
data class IapTransaction(
    @Contextual
    val id: UUID = UUID.NIL,
    val platform: IapPlatform,
    @ColumnName("transaction_id")
    val transactionId: String,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID,
    @Contextual
    @ColumnName("plan_id")
    val planId: UUID,
    @Contextual
    @ColumnName("subscription_id")
    val subscriptionId: UUID? = null,
    @ColumnName("product_id")
    val productId: String? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
)
