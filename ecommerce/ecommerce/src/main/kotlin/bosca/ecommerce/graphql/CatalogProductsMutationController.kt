package bosca.ecommerce.graphql

import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductInput
import bosca.ecommerce.service.CatalogProductService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Catalog-entry creates and the per-entry instance accessor under `EcomMutation.catalogProducts`. Admin-gated. */
@TypeController
class CatalogProductsMutationController(
    private val catalogProductService: CatalogProductService,
    private val groups: GroupEvaluator,
) : GraphQLController<CatalogProductsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: CatalogProductInput): CatalogProduct {
        groups.verifyEcomAdmin(authentication)
        return catalogProductService.create(input, authentication.principal()?.id)
    }

    @Field
    fun catalogProduct(id: UUID): CatalogProductMutation = CatalogProductMutation(id)
}
