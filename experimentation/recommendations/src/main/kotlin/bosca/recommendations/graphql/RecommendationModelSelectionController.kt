package bosca.recommendations.graphql

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Artifact-authorized discovery of global model selections, without administrative configuration. */
object RecommendationModelSelection

@TypeController
class RecommendationModelSelectionController(
    private val strategyService: RecommendationStrategyService,
    private val flagService: FeatureFlagService,
    private val experimentService: ExperimentService,
    private val groupEvaluator: GroupEvaluator,
    private val artifactPermissionEvaluator: ArtifactPermissionEvaluator,
) : GraphQLController<RecommendationModelSelection> {

    /** Returns active pins and running experiment versions that the caller may pull. */
    @Field
    suspend fun personalizedVersions(authentication: AuthenticationContext): List<Long> {
        val scoped = authentication.principal() is ScopedAuthenticatedPrincipal
        if (!scoped) groupEvaluator.verifyHasAdminGroup(authentication)
        val versions = sortedSetOf<Long>()
        var offset = 0L
        do {
            val strategies = strategyService.getAll(offset, 100)
            for (strategy in strategies) {
                if (strategy.type == RecommendationStrategyType.PERSONALIZED &&
                    strategy.status == RecommendationStrategyStatus.ACTIVE
                ) {
                    positiveVersion((strategy.configuration as? JsonObject)?.get("modelVersion"))?.let(versions::add)
                }
            }
            offset += strategies.size
        } while (strategies.size == 100)
        val flag = flagService.getByKey("recommendation-model")
        if (flag?.status == FlagStatus.ENABLED &&
            experimentService.getByFlagId(flag.id).any { it.status == ExperimentStatus.RUNNING }
        ) {
            for (variation in flag.variations as? JsonArray ?: JsonArray(emptyList())) {
                positiveVersion((variation as? JsonObject)?.get("value"))?.let(versions::add)
            }
        }
        return versions.filter { version ->
            !scoped || artifactPermissionEvaluator.evaluate(
                authentication, "ml", "model", "recommender-personalized", version.toString(), ArtifactAction.PULL,
            )
        }
    }

    private fun positiveVersion(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return try {
            BigDecimal(primitive.content).longValueExact().takeIf { it > 0 }
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}
