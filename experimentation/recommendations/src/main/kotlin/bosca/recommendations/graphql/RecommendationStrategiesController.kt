package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object RecommendationStrategies

/**
 * Provides admin-only GraphQL query fields for listing and inspecting recommendation
 * strategies. Strategies define the rules and data sources (analytics queries, content
 * curation, trending signals) used to generate recommendation candidates.
 */
@TypeController
class RecommendationStrategiesController(
    private val strategyService: RecommendationStrategyService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<RecommendationStrategies> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<RecommendationStrategy> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return strategyService.getAll(maxOf(offset, 0), limit.coerceIn(1, 100))
    }

    @Field
    suspend fun strategy(authentication: AuthenticationContext, id: UUID): RecommendationStrategy? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return strategyService.getById(id)
    }
}
