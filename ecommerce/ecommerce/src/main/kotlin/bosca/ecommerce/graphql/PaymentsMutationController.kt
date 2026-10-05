package bosca.ecommerce.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Payment administration under `EcomMutation.payments`. The per-payment accessor is admin-gated. */
@TypeController
class PaymentsMutationController : GraphQLController<PaymentsMutation> {

    @Field
    fun payment(id: UUID): PaymentMutation = PaymentMutation(id)
}
