package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogInput
import bosca.ecommerce.service.CatalogService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one catalog (the id carried by [CatalogMutation]). Admin-gated. */
@TypeController
class CatalogMutationController(
    private val catalogService: CatalogService,
    private val groups: GroupEvaluator,
) : GraphQLController<CatalogMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: CatalogMutation, input: CatalogInput): Catalog {
        groups.verifyEcomAdmin(authentication)
        return catalogService.edit(source.id, input, authentication.principal()?.id)
    }
}
