package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for catalog creates + the per-catalog instance accessor. */
object CatalogsMutation

/** Id-scoped mutation namespace for one catalog. */
data class CatalogMutation(val id: UUID)

/** GraphQL namespace marker for manufacturer creates. */
object ManufacturersMutation
