package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogInput
import bosca.ecommerce.service.CatalogService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Catalog creates and the per-catalog instance accessor under `EcomMutation.catalogs`. Admin-gated. */
@TypeController
class CatalogsMutationController(
    private val catalogService: CatalogService,
    private val groups: GroupEvaluator,
) : GraphQLController<CatalogsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: CatalogInput): Catalog {
        groups.verifyEcomAdmin(authentication)
        return catalogService.create(input, authentication.principal()?.id)
    }

    @Field
    fun catalog(id: UUID): CatalogMutation = CatalogMutation(id)
}
