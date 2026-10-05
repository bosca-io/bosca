package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates a fulfillment center. */
@Serializable
data class FulfillmentCenterInput(
    @Contextual
    val companyId: UUID,
    val name: String,
    val connectorKey: String,
    @Contextual
    val shippingProviderId: UUID,
    val address1: String,
    val address2: String? = null,
    val city: String,
    val state: String,
    val country: String,
    val zip: String,
)

/** Creates an inventory row for a product at a fulfillment center. */
@Serializable
data class InventoryInput(
    @Contextual
    val productId: UUID,
    @Contextual
    val fulfillmentCenterId: UUID,
    val sku: String,
    val quantity: Int,
)
