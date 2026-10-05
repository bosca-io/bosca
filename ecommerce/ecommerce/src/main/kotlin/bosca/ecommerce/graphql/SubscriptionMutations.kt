package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for subscription plan-group + plan admin CRUD. */
object PlansMutation

/** GraphQL namespace marker for subscription create + per-subscription operations. */
object SubscriptionsMutation

/** Id-scoped mutation namespace for one subscription. */
data class SubscriptionMutation(val id: UUID)
