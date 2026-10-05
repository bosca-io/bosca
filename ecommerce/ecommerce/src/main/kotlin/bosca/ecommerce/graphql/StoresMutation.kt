package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for store creates + the per-store instance accessor. */
object StoresMutation

/** Id-scoped mutation namespace for one store. */
data class StoreMutation(val id: UUID)

/** GraphQL namespace marker for payment/shipping provider registration. */
object ProvidersMutation
