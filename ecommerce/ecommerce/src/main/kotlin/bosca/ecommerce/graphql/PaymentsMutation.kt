package bosca.ecommerce.graphql

import bosca.serialization.UUID

/** GraphQL namespace marker for payment admin operations. */
object PaymentsMutation

/** Id-scoped mutation namespace for one payment (refund/void/refund-to-credit). */
data class PaymentMutation(val id: UUID)
