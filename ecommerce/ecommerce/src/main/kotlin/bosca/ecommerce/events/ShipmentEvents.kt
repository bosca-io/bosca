package bosca.ecommerce.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Shipment lifecycle events (`bosca.ecommerce.shipment.*`). Emitted from the fulfillment flow when a
 * paid order is split into shipments (created) and, in Phase 2, when one ships. Each carries [storeId]
 * as the tenant-scoping filter; `jobs = []` because ecom enqueues no work of its own — these publish
 * to PubSub and feed the trigger/pipeline dispatcher.
 */
@Serializable
sealed class ShipmentEvent : Event {
    @Contextual
    abstract val shipmentId: UUID

    @Contextual
    abstract val storeId: UUID

    override fun identityKey(): Any = shipmentId
}

const val SHIPMENT_CREATED_CHANNEL = "bosca.ecommerce.shipment.created"
const val SHIPMENT_SHIPPED_CHANNEL = "bosca.ecommerce.shipment.shipped"

@JobEvent(
    jobs = [],
    pubsubChannel = SHIPMENT_CREATED_CHANNEL,
    displayName = "Shipment Created",
    description = "A paid order produced a shipment awaiting dispatch from a fulfillment center.",
)
@Serializable
data class ShipmentCreated(
    @Contextual override val shipmentId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val cartId: UUID,
    @Contextual val companyId: UUID,
    @Contextual val fulfillmentCenterId: UUID,
) : ShipmentEvent()

@JobEvent(
    jobs = [],
    pubsubChannel = SHIPMENT_SHIPPED_CHANNEL,
    displayName = "Shipment Shipped",
    description = "A shipment was dispatched from its fulfillment center; its inventory was drawn down.",
)
@Serializable
data class ShipmentShipped(
    @Contextual override val shipmentId: UUID,
    @Contextual override val storeId: UUID,
    @Contextual val cartId: UUID,
    @Contextual val companyId: UUID,
    @Contextual val fulfillmentCenterId: UUID,
) : ShipmentEvent()
