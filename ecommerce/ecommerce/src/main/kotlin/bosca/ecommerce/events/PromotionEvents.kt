package bosca.ecommerce.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

const val PROMOTION_REDEEMED_CHANNEL = "bosca.ecommerce.promotion.redeemed"

/**
 * A promotion code was applied to a cart (`bosca.ecommerce.promotion.redeemed`), emitted from
 * `PromotionServiceImpl.applyToCart` after the redemption is reserved against the availability cap.
 */
@JobEvent(
    jobs = [],
    pubsubChannel = PROMOTION_REDEEMED_CHANNEL,
    displayName = "Promotion Redeemed",
    description = "A promotion code was redeemed against a cart.",
)
@Serializable
data class PromotionRedeemed(
    @Contextual val promotionId: UUID,
    @Contextual val storeId: UUID,
    @Contextual val accountId: UUID,
    val code: String,
    @Contextual val cartId: UUID,
) : Event {
    override fun identityKey(): Any = promotionId
}
