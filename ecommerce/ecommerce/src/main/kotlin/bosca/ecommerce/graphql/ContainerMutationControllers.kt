package bosca.ecommerce.graphql

import bosca.ecommerce.model.Container
import bosca.ecommerce.model.ContainerInput
import bosca.ecommerce.service.ContainerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Container creates + the per-container accessor. Admin-gated. */
@TypeController
class ContainersMutationController(
    private val containerService: ContainerService,
    private val groups: GroupEvaluator,
) : GraphQLController<ContainersMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: ContainerInput): Container {
        groups.verifyEcomAdmin(authentication)
        return containerService.add(input, authentication.principal()?.id)
    }

    @Field
    fun container(id: UUID): ContainerMutation = ContainerMutation(id)
}

/** Operations on one container (the id carried by [ContainerMutation]). Admin-gated. */
@TypeController
class ContainerMutationController(
    private val containerService: ContainerService,
    private val groups: GroupEvaluator,
) : GraphQLController<ContainerMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: ContainerMutation, input: ContainerInput): Container {
        groups.verifyEcomAdmin(authentication)
        return containerService.edit(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: ContainerMutation): Boolean {
        groups.verifyEcomAdmin(authentication)
        return containerService.delete(source.id, authentication.principal()?.id)
    }
}
