package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for account creates + the per-account instance accessor. */
object AccountsMutation

/** Id-scoped mutation namespace for one account. */
data class AccountMutation(val id: UUID)
