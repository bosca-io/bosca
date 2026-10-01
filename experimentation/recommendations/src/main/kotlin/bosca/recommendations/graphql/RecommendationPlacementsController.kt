package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.service.RecommendationPlacementService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object RecommendationPlacements

/**
 * Provides admin-only GraphQL queries for browsing and looking up recommendation placements.
 * Placements define named display slots (identified by ID or slug) where the client renders
 * recommendations, each configured with a maximum item count and rendering options.
 */
@TypeController
class RecommendationPlacementsController(
    private val placementService: RecommendationPlacementService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<RecommendationPlacements> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<RecommendationPlacement> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return placementService.getAll()
    }

    @Field
    suspend fun placement(authentication: AuthenticationContext, id: UUID): RecommendationPlacement? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return placementService.getById(id)
    }

    @Field
    suspend fun placementBySlug(authentication: AuthenticationContext, slug: String): RecommendationPlacement? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return placementService.getBySlug(slug)
    }
}
