package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** Id-scoped mutation namespace for one manufacturer. */
data class ManufacturerMutation(val id: UUID)
