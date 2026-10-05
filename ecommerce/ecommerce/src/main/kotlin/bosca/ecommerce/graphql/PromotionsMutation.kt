package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for promotion admin CRUD. */
object PromotionsMutation

/** Id-scoped mutation namespace for one promotion. */
data class PromotionMutation(val id: UUID)
