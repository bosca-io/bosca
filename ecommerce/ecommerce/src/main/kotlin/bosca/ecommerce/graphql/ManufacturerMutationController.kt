package bosca.ecommerce.graphql

import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerInput
import bosca.ecommerce.service.ManufacturerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one manufacturer (the id carried by [ManufacturerMutation]). Admin-gated. */
@TypeController
class ManufacturerMutationController(
    private val manufacturerService: ManufacturerService,
    private val groups: GroupEvaluator,
) : GraphQLController<ManufacturerMutation> {

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        source: ManufacturerMutation,
        input: ManufacturerInput,
    ): Manufacturer {
        groups.verifyEcomAdmin(authentication)
        return manufacturerService.update(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: ManufacturerMutation): Boolean {
        groups.verifyEcomAdmin(authentication)
        return manufacturerService.delete(source.id, authentication.principal()?.id)
    }
}
