package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.model.RecommendationPlacementInput
import bosca.recommendations.service.RecommendationPlacementService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object RecommendationPlacementsMutation

/**
 * Provides admin-only GraphQL mutations for creating, editing, and deleting recommendation
 * placements. Each mutation transactionally updates the placement record together with its
 * ordered list of linked strategy IDs, ensuring atomic configuration changes.
 */
@TypeController
class RecommendationPlacementsMutationController(
    private val placementService: RecommendationPlacementService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<RecommendationPlacementsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, placement: RecommendationPlacementInput, strategyIds: List<UUID>): RecommendationPlacement {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return placementService.add(placement, strategyIds)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, placement: RecommendationPlacementInput, strategyIds: List<UUID>): RecommendationPlacement {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return placementService.edit(id, placement, strategyIds)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        placementService.delete(id)
        return true
    }
}
