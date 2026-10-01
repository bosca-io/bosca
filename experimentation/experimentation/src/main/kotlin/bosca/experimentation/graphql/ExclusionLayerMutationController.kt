package bosca.experimentation.graphql

import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.ExclusionLayerInput
import bosca.experimentation.service.ExclusionLayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object ExclusionLayersMutation

@TypeController
class ExclusionLayerMutationController(
    private val exclusionLayerService: ExclusionLayerService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<ExclusionLayersMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, layer: ExclusionLayerInput): ExclusionLayer {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return exclusionLayerService.add(layer)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, layer: ExclusionLayerInput): ExclusionLayer {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return exclusionLayerService.edit(id, layer)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        exclusionLayerService.delete(id)
        return true
    }
}
