package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A recurring purchase (`ecom.subscriptions`). [price]/[interval]/[intervalUnit] are SNAPSHOTTED at
 * signup (the plan may change later). [renews] is the next billing instant; the renewal sweep charges
 * the saved method in [extras] and advances it. [paymentFailures] counts consecutive renewal
 * failures (the plan group's retry cap sends it UNPAID). [cartId] records the originating checkout;
 * [nextSubscriptionId] chains plan migrations.
 */
@BatchKey("id")
@Serializable
data class Subscription(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID,
    @Contextual
    @ColumnName("plan_id")
    val planId: UUID,
    @Contextual
    @ColumnName("plan_group_id")
    val planGroupId: UUID,
    @Contextual
    @ColumnName("cart_id")
    val cartId: UUID? = null,
    val status: SubscriptionStatus = SubscriptionStatus.PENDING,
    val price: Money,
    /** The ISO-4217 currency of this subscription's price (stamped from the store at creation). */
    val currency: String = "USD",
    val interval: Int,
    @ColumnName("interval_unit")
    val intervalUnit: IntervalUnit,
    @Contextual
    val renews: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val expires: OffsetDateTime? = null,
    @ColumnName("payment_failures")
    val paymentFailures: Int = 0,
    val renewals: Int = 0,
    /** true = externally billed (e.g. app-store IAP); the renewal sweep never charges or duns it. */
    @ColumnName("external")
    val external: Boolean = false,
    @Contextual
    @ColumnName("last_payment_id")
    val lastPaymentId: UUID? = null,
    @Contextual
    @ColumnName("next_subscription_id")
    val nextSubscriptionId: UUID? = null,
    @property:DbMapper(JsonbMapper::class)
    val extras: SubscriptionExtras = StandardSubscriptionExtras(),
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
