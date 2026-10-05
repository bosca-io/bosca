package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for fulfillment-center + inventory admin operations. */
object FulfillmentMutation

/** Id-scoped mutation namespace for one inventory row. */
data class InventoryMutation(val id: UUID)

/** Id-scoped mutation namespace for one shipment. */
data class ShipmentMutation(val id: UUID)
