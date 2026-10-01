package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates a subscription plan group. */
@Serializable
data class PlanGroupInput(
    @Contextual
    val storeId: UUID,
    val key: String,
    val name: String,
    val description: String = "",
    val paymentRetries: Int = 3,
)

/** Creates a subscription plan within a group. */
@Serializable
data class PlanInput(
    @Contextual
    val planGroupId: UUID,
    @Contextual
    val storeId: UUID,
    val key: String,
    val name: String,
    val description: String = "",
    val price: Money,
    val interval: Int,
    val intervalUnit: IntervalUnit,
    val configuration: PlanConfiguration = StandardPlanConfiguration,
)

/** Subscribes an account to a plan directly (the admin/API path). [saved] enables renewals. */
@Serializable
data class SubscribeInput(
    @Contextual
    val storeId: UUID,
    @Contextual
    val accountId: UUID,
    @Contextual
    val planId: UUID,
    val saved: SavedPaymentMethod? = null,
)
