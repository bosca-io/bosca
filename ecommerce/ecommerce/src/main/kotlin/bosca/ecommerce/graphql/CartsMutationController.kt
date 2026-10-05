package bosca.ecommerce.graphql

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.service.CartService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/** Cart creation and the per-cart accessor under `EcomMutation.carts`. */
@TypeController
class CartsMutationController(
    private val cartService: CartService,
    private val cartAccess: CartAccessEvaluator,
) : GraphQLController<CartsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CartInput): Cart {
        cartAccess.verifyCreate(authentication, input)
        return cartService.create(input, authentication.principal()?.id)
    }

    @Field
    fun cart(id: UUID): CartMutation = CartMutation(id)
}
