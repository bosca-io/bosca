package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.service.RecommendationPlacementService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the RecommendationPlacement GraphQL type. Scalar fields are passed through
 * from the model; the ordered list of [strategies] linked to a placement is resolved on demand,
 * allowing admin clients to inspect which scoring strategies feed a given display slot.
 */
@TypeController
class RecommendationPlacementController(
    private val placementService: RecommendationPlacementService,
    private val strategyService: RecommendationStrategyService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<RecommendationPlacement> {

    @Field
    fun id(placement: RecommendationPlacement): UUID = placement.id

    @Field
    fun name(placement: RecommendationPlacement): String = placement.name

    @Field
    fun description(placement: RecommendationPlacement): String = placement.description

    @Field
    fun slug(placement: RecommendationPlacement): String = placement.slug

    @Field
    fun maxItems(placement: RecommendationPlacement): Int = placement.maxItems

    @Field
    fun configuration(placement: RecommendationPlacement): JsonElement? = placement.configuration

    @Field
    fun created(placement: RecommendationPlacement): OffsetDateTime = placement.created

    @Field
    fun modified(placement: RecommendationPlacement): OffsetDateTime = placement.modified

    @Field
    suspend fun strategies(authentication: AuthenticationContext, placement: RecommendationPlacement): List<RecommendationStrategy> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val strategyIds = placementService.getStrategyIds(placement.id)
        return strategyService.getByIds(strategyIds)
    }
}
