package bosca.ecommerce.graphql

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartSubmitResult
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.Subscription
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Field wiring for the `CartSubmitResult` GraphQL type. */
@TypeController
class CartSubmitResultController : GraphQLController<CartSubmitResult> {

    @Field
    fun cart(source: CartSubmitResult): Cart = source.cart

    @Field
    fun payments(source: CartSubmitResult): List<Payment> = source.payments

    @Field
    fun subscriptions(source: CartSubmitResult): List<Subscription> = source.subscriptions
}
