package bosca.experimentation.graphql

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.repository.ConversionGoalRepository
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves fields on the ExperimentResult GraphQL type. Variations are referenced
 * by their stable string key — there is no separate variant table to look up.
 * Clients fetch the parent flag's variations once and resolve the [variationKey]
 * client-side to display name/value.
 */
@TypeController(type = "ExperimentResult")
class ExperimentResultTypeController(
    private val conversionGoalRepository: ConversionGoalRepository
) : GraphQLController<ExperimentResult> {

    /**
     * Global cache with TTL-based expiration for conversion goal lookups.
     * A typical results query resolves `goal` on 50-100 result rows, but
     * those rows share only 2-3 distinct goal IDs. Without this cache each
     * row fires a separate `SELECT`; with it the actual DB round-trips
     * collapse to one per distinct goal ID. Note: because this is a global
     * cache (not request-scoped), a goal edited in one request may serve
     * stale data to other requests until the TTL expires.
     */
    private val goalCache = ServiceCache<UUID, ConversionGoal>(
        cacheName = "experimentation:goal:byid",
        serializer = UUIDKeySerializer,
    ) { id ->
        conversionGoalRepository.getById(id)
    }

    @Field
    fun id(experimentResult: ExperimentResult): UUID = experimentResult.id

    @Field
    fun experimentId(experimentResult: ExperimentResult): UUID = experimentResult.experimentId

    @Field
    fun variationKey(experimentResult: ExperimentResult): String = experimentResult.variationKey

    @Field
    fun goalId(experimentResult: ExperimentResult): UUID = experimentResult.goalId

    @Field
    suspend fun goal(experimentResult: ExperimentResult): ConversionGoal? {
        return goalCache.get(experimentResult.goalId)
    }

    @Field
    fun assignments(experimentResult: ExperimentResult): Long = experimentResult.assignments

    @Field
    fun impressions(experimentResult: ExperimentResult): Long = experimentResult.impressions

    @Field
    fun observationCount(experimentResult: ExperimentResult): Long = experimentResult.observationCount

    @Field
    fun conversions(experimentResult: ExperimentResult): Long = experimentResult.conversions

    @Field
    fun conversionRate(experimentResult: ExperimentResult): Double = experimentResult.conversionRate

    @Field
    fun confidenceLevel(experimentResult: ExperimentResult): Double? = experimentResult.confidenceLevel

    @Field
    fun liftOverControl(experimentResult: ExperimentResult): Double? = experimentResult.liftOverControl

    @Field
    fun mean(experimentResult: ExperimentResult): Double? = experimentResult.mean

    @Field
    fun variance(experimentResult: ExperimentResult): Double? = experimentResult.variance

    @Field
    fun probabilityBeatsControl(experimentResult: ExperimentResult): Double? = experimentResult.probabilityBeatsControl

    @Field
    fun expectedLoss(experimentResult: ExperimentResult): Double? = experimentResult.expectedLoss

    @Field
    fun adjustedMean(experimentResult: ExperimentResult): Double? = experimentResult.adjustedMean

    @Field
    fun adjustedVariance(experimentResult: ExperimentResult): Double? = experimentResult.adjustedVariance

    @Field
    fun updatedAt(experimentResult: ExperimentResult): OffsetDateTime = experimentResult.updatedAt
}
