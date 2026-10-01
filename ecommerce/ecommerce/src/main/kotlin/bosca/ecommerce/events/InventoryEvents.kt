package bosca.ecommerce.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Inventory movement events (`bosca.ecommerce.inventory.*`), emitted from `InventoryServiceImpl`.
 * [InventoryShipped] fires when on-hand stock decrements at fulfillment; [InventoryReservationReleased]
 * fires when an in-cart or pending hold is freed (cart edit, removal, or expiration).
 */
@Serializable
sealed class InventoryEvent : Event {
    @Contextual
    abstract val inventoryId: UUID

    @Contextual
    abstract val productId: UUID

    override fun identityKey(): Any = inventoryId
}

const val INVENTORY_SHIPPED_CHANNEL = "bosca.ecommerce.inventory.shipped"
const val INVENTORY_RESERVATION_RELEASED_CHANNEL = "bosca.ecommerce.inventory.reservation_released"

@JobEvent(
    jobs = [],
    pubsubChannel = INVENTORY_SHIPPED_CHANNEL,
    displayName = "Inventory Shipped",
    description = "On-hand stock was shipped and decremented.",
)
@Serializable
data class InventoryShipped(
    @Contextual override val inventoryId: UUID,
    @Contextual override val productId: UUID,
    val quantity: Int,
) : InventoryEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = INVENTORY_RESERVATION_RELEASED_CHANNEL,
    displayName = "Inventory Reservation Released",
    description = "An inventory hold (in-cart or pending) was released back to available stock.",
)
@Serializable
data class InventoryReservationReleased(
    @Contextual override val inventoryId: UUID,
    @Contextual override val productId: UUID,
    val quantity: Int,
) : InventoryEvent()
