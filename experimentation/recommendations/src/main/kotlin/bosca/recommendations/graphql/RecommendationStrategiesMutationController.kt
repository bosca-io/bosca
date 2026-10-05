package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.jobs.BackfillRecommendationEmbeddingsJob
import bosca.recommendations.jobs.TrainModelJob
import bosca.recommendations.jobs.TrainModelJobExecutor
import bosca.recommendations.jobs.enqueue
import bosca.recommendations.model.EngineExperimentProvisioning
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.recommendations.service.RecommendationExperimentService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

object RecommendationStrategiesMutation

/**
 * Provides admin-only GraphQL mutations for the full lifecycle of recommendation strategies:
 * creation, editing, deletion, and on-demand evaluation. Triggering evaluation causes the
 * strategy's analytics query to run and its resulting scores to be persisted as recommendations.
 */
@TypeController
class RecommendationStrategiesMutationController(
    private val strategyService: RecommendationStrategyService,
    private val experimentService: RecommendationExperimentService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<RecommendationStrategiesMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, strategy: RecommendationStrategyInput): RecommendationStrategy {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return strategyService.add(strategy)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, strategy: RecommendationStrategyInput): RecommendationStrategy {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return strategyService.edit(id, strategy)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        strategyService.delete(id)
        return true
    }

    @Field
    suspend fun evaluate(authentication: AuthenticationContext, strategyId: UUID): RecommendationStrategy {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return strategyService.evaluate(strategyId)
    }

    /**
     * One-click setup of the built-in ML-vs-heuristic A/B test (flag + 50/50 split + experiment + goals),
     * created dormant. Idempotent — returns the existing experiment if already provisioned.
     */
    @Field
    suspend fun provisionEngineExperiment(authentication: AuthenticationContext): EngineExperimentProvisioning {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.provisionEngineExperiment()
    }

    /**
     * One-click setup of the model-vs-model online A/B test: routes traffic 50/50 between the two given
     * model versions and measures which wins on engagement. Idempotent — re-points the test to the
     * requested version pair. Created dormant.
     */
    @Field
    suspend fun provisionModelExperiment(
        authentication: AuthenticationContext,
        championVersion: Long,
        challengerVersion: Long,
    ): EngineExperimentProvisioning {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return experimentService.provisionModelExperiment(championVersion, challengerVersion)
    }

    /**
     * Enqueue a TFRS model training run on demand (admin). The recommendations runner picks up the job and
     * POSTs to the trainer service, so training runs in the background — this returns as soon as the run is
     * enqueued. [configuration] optionally overrides the trainer's defaults (e.g. epochs, lookbackDays,
     * embeddingDim) as a JSON object; null uses the defaults. Mirrors the daily scheduled `train-model` job.
     */
    @Field
    suspend fun trainModel(authentication: AuthenticationContext, configuration: JsonElement?): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        TrainModelJob(configuration).enqueue()
        return true
    }

    /** Enqueues a durable semantic-data backfill for recommendation-eligible content. */
    @Field
    suspend fun backfillSemanticData(
        authentication: AuthenticationContext,
        overwriteExisting: Boolean,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        BackfillRecommendationEmbeddingsJob(overwriteExisting = overwriteExisting).enqueue()
        return true
    }
}
