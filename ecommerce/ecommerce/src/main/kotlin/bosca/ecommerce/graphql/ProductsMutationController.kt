package bosca.ecommerce.graphql

import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.ecommerce.service.ProductService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Product creates and the per-product instance accessor under `EcomMutation.products`. Admin-gated. */
@TypeController
class ProductsMutationController(
    private val productService: ProductService,
    private val groups: GroupEvaluator,
) : GraphQLController<ProductsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: ProductInput): Product {
        groups.verifyEcomAdmin(authentication)
        return productService.create(input, authentication.principal()?.id)
    }

    @Field
    fun product(id: UUID): ProductMutation = ProductMutation(id)
}
