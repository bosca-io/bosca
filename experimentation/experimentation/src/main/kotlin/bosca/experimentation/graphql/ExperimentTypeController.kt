package bosca.experimentation.graphql

import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.AnalysisMethod
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.service.ExclusionLayerService
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Resolves fields on the Experiment GraphQL type. Note that experiments no longer
 * own variants — variants are now variations stored on the parent flag, and the
 * experiment merely references which targeting rule it observes via [Experiment.targetingRuleId].
 */
@TypeController(type = "Experiment")
class ExperimentTypeController(
    private val featureFlagService: FeatureFlagService,
    private val experimentService: ExperimentService,
    private val exclusionLayerService: ExclusionLayerService,
    private val json: Json,
) : GraphQLController<Experiment> {


    @Field
    fun id(experiment: Experiment): UUID = experiment.id

    @Field
    fun featureFlagId(experiment: Experiment): UUID = experiment.featureFlagId

    @Field
    suspend fun featureFlag(experiment: Experiment): FeatureFlag? {
        return featureFlagService.getById(experiment.featureFlagId)
    }

    @Field
    fun name(experiment: Experiment): String = experiment.name

    @Field
    fun description(experiment: Experiment): String = experiment.description

    @Field
    fun hypothesis(experiment: Experiment): String = experiment.hypothesis

    @Field
    fun status(experiment: Experiment): ExperimentStatus = experiment.status

    @Field
    fun targetingRuleId(experiment: Experiment): String? = experiment.targetingRuleId

    @Field
    fun controlVariationKey(experiment: Experiment): String = experiment.controlVariationKey

    @Field
    fun excludedPrincipalIds(experiment: Experiment): List<UUID> = experiment.excludedPrincipalIds

    @Field
    fun activationFilter(experiment: Experiment): ExperimentActivationFilter? = experiment.activationFilter

    @Field
    suspend fun exclusionLayer(experiment: Experiment): ExclusionLayer? {
        val layerId = experiment.exclusionLayerId ?: return null
        return exclusionLayerService.getById(layerId)
    }

    /**
     * Returns a page of conversion goals attached to this experiment.
     * Pagination is pushed down to the underlying SQL query; the server
     * clamps `limit` to [MAX_GOALS_PAGE] and treats negative offsets as 0.
     */
    @Field
    suspend fun conversionGoals(experiment: Experiment, offset: Long, limit: Int): List<ConversionGoal> {
        val safeOffset = maxOf(offset, 0L)
        val safeLimit = limit.coerceIn(1, MAX_GOALS_PAGE)
        return experimentService.getConversionGoals(experiment.id, safeOffset, safeLimit)
    }

    /**
     * Returns a page of aggregated experiment results. Pagination is
     * pushed down to the underlying SQL query; the server clamps `limit`
     * to [MAX_RESULTS_PAGE] and treats negative offsets as 0.
     */
    @Field
    suspend fun results(experiment: Experiment, offset: Long, limit: Int): List<ExperimentResult> {
        val safeOffset = maxOf(offset, 0L)
        val safeLimit = limit.coerceIn(1, MAX_RESULTS_PAGE)
        return experimentService.getResults(experiment.id, safeOffset, safeLimit)
    }

    /**
     * Returns the current-definition report first when one exists, followed by
     * historical reports newest first. Pagination is pushed down to the SQL
     * query; the server clamps `limit` to [MAX_REPORTS_PAGE] and treats negative
     * offsets as 0.
     */
    @Field
    suspend fun analysisReports(experiment: Experiment, offset: Long, limit: Int): List<AnalysisReport> {
        val safeOffset = maxOf(offset, 0L)
        val safeLimit = limit.coerceIn(1, MAX_REPORTS_PAGE)
        return experimentService.getAnalysisReportsForRevision(
            experiment.id,
            experiment.controlVariationKey,
            experiment.analysisRevision,
            safeOffset,
            safeLimit,
        )
            .map { report -> report.withCurrentDefinitionMarker(experiment) }
    }

    companion object {
        private const val MAX_GOALS_PAGE = 100
        private const val MAX_RESULTS_PAGE = 200
        private const val MAX_REPORTS_PAGE = 100
    }

    @Field
    fun startDate(experiment: Experiment): OffsetDateTime? = experiment.startDate

    @Field
    fun endDate(experiment: Experiment): OffsetDateTime? = experiment.endDate

    @Field
    fun targetSampleSize(experiment: Experiment): Long? = experiment.targetSampleSize

    @Field
    fun created(experiment: Experiment): OffsetDateTime = experiment.created

    @Field
    fun modified(experiment: Experiment): OffsetDateTime = experiment.modified

    /**
     * Decodes the experiment's stored rollout policy JSON into the
     * typed [RolloutPolicy] shape the GraphQL schema exposes. A
     * malformed policy throws rather than silently resolving to
     * null — an operator querying this field with a broken stored
     * policy needs the error to surface, not be swallowed.
     */
    @Field
    fun rolloutPolicy(experiment: Experiment): RolloutPolicy? {
        val element = experiment.rolloutPolicy ?: return null
        return json.decodeFromJsonElement(RolloutPolicy.serializer(), element)
    }

    /**
     * Returns a page of rollout controller audit events for this
     * experiment, newest first. Pagination is pushed down to the
     * repository; `limit` is clamped to [MAX_EVENTS_PAGE] and a
     * negative `offset` is treated as 0.
     */
    @Field
    suspend fun rolloutPolicyEvents(experiment: Experiment, offset: Long, limit: Int): List<RolloutPolicyEvent> {
        val safeOffset = maxOf(offset, 0L)
        val safeLimit = limit.coerceIn(1, MAX_EVENTS_PAGE)
        return experimentService.getRolloutPolicyEvents(experiment.id, safeOffset, safeLimit)
    }

    @Field
    fun analysisMethod(experiment: Experiment): AnalysisMethod = experiment.analysisMethod

    /**
     * Decodes the experiment's stored Bayesian prior blob. A
     * malformed prior throws; see [rolloutPolicy] for the
     * rationale — surface the bug rather than swallow it.
     */
    @Field
    fun bayesianPrior(experiment: Experiment): BayesianPrior? {
        val element = experiment.bayesianPrior ?: return null
        return json.decodeFromJsonElement(BayesianPrior.serializer(), element)
    }
}

private const val MAX_EVENTS_PAGE = 100

/**
 * Adds a presentation-only currentness marker to the report's existing JSON
 * details. The experiment revision remains an internal persistence concern;
 * clients receive the decision they need without duplicating revision logic or
 * exposing another GraphQL field. Malformed and legacy details remain visible
 * as history and are never promoted to current.
 */
private fun AnalysisReport.withCurrentDefinitionMarker(experiment: Experiment): AnalysisReport {
    val objectDetails = details as? JsonObject ?: return this
    val control = (objectDetails["controlVariationKey"] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
    val revision = (objectDetails["experimentRevision"] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull
    val isCurrent = control == experiment.controlVariationKey && revision == experiment.analysisRevision
    return copy(details = JsonObject(objectDetails + ("isCurrent" to JsonPrimitive(isCurrent))))
}
