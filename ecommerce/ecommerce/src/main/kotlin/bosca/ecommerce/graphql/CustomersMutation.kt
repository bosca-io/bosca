package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for customer creates + the per-customer instance accessor. */
object CustomersMutation

/** Id-scoped mutation namespace for one customer. */
data class CustomerMutation(val id: UUID)
