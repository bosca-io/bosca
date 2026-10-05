package bosca.ecommerce.graphql

import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreInput
import bosca.ecommerce.service.StoreService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one store (the id carried by [StoreMutation]). Admin-gated. */
@TypeController
class StoreMutationController(
    private val storeService: StoreService,
    private val groups: GroupEvaluator,
) : GraphQLController<StoreMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: StoreMutation, input: StoreInput): Store {
        groups.verifyEcomAdmin(authentication)
        return storeService.edit(source.id, input, authentication.principal()?.id)
    }
}
