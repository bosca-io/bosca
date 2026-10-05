package bosca.ecommerce.graphql

import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductInput
import bosca.ecommerce.service.CatalogProductService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one catalog entry (the id carried by [CatalogProductMutation]). Admin-gated. */
@TypeController
class CatalogProductMutationController(
    private val catalogProductService: CatalogProductService,
    private val groups: GroupEvaluator,
) : GraphQLController<CatalogProductMutation> {

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        source: CatalogProductMutation,
        input: CatalogProductInput,
    ): CatalogProduct {
        groups.verifyEcomAdmin(authentication)
        return catalogProductService.edit(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: CatalogProductMutation): Boolean {
        groups.verifyEcomAdmin(authentication)
        return catalogProductService.delete(source.id, authentication.principal()?.id)
    }
}
