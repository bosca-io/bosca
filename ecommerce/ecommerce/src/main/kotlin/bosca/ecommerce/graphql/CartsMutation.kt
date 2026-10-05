package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for cart creation + the per-cart instance accessor. */
object CartsMutation

/** Id-scoped mutation namespace for one cart. */
data class CartMutation(val id: UUID)
