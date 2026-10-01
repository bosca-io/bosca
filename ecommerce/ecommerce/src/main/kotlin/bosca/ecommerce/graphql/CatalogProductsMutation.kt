package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for catalog-entry creates + the per-entry instance accessor. */
object CatalogProductsMutation

/** Id-scoped mutation namespace for one catalog entry. */
data class CatalogProductMutation(val id: UUID)
