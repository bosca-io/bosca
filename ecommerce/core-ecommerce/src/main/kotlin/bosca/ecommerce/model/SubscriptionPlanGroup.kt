package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A family of subscription plans (`ecom.subscription_plan_groups`), keyed `(store, key)`. An account
 * may hold only one active subscription per group (the no-double-subscribe boundary).
 * [paymentRetries] caps renewal payment attempts before a subscription goes UNPAID.
 */
@BatchKey("id")
@Serializable
data class SubscriptionPlanGroup(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    val key: String,
    val name: String,
    val description: String = "",
    @ColumnName("payment_retries")
    val paymentRetries: Int = 3,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
