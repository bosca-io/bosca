package bosca.ecommerce.graphql

import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreInput
import bosca.ecommerce.service.StoreService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Store creates and the per-store instance accessor under `EcomMutation.stores`. Admin-gated. */
@TypeController
class StoresMutationController(
    private val storeService: StoreService,
    private val groups: GroupEvaluator,
) : GraphQLController<StoresMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: StoreInput): Store {
        groups.verifyEcomAdmin(authentication)
        return storeService.create(input, authentication.principal()?.id)
    }

    @Field
    fun store(id: UUID): StoreMutation = StoreMutation(id)
}
