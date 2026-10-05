package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for product creates + the per-product instance accessor. */
object ProductsMutation

/** Id-scoped mutation namespace for one product. */
data class ProductMutation(val id: UUID)
