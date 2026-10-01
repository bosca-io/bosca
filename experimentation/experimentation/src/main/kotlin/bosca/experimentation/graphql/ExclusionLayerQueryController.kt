package bosca.experimentation.graphql

import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.service.ExclusionLayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object ExclusionLayers

@TypeController
class ExclusionLayerQueryController(
    private val exclusionLayerService: ExclusionLayerService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<ExclusionLayers> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<ExclusionLayer> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return exclusionLayerService.getAll()
    }

    @Field
    suspend fun layer(authentication: AuthenticationContext, id: UUID): ExclusionLayer? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return exclusionLayerService.getById(id)
    }
}
