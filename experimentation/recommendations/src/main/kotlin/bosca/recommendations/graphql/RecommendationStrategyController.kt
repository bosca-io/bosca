package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.scheduler.service.SchedulerService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the RecommendationStrategy GraphQL type. Scalar fields are passed through
 * from the model; the cron [evaluationSchedule] of its scheduled evaluation job is resolved on
 * demand, enabling admin UIs to display the full configuration of each strategy.
 */
@TypeController
class RecommendationStrategyController(
    private val schedulerService: SchedulerService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<RecommendationStrategy> {

    @Field
    fun id(strategy: RecommendationStrategy): UUID = strategy.id

    @Field
    fun name(strategy: RecommendationStrategy): String = strategy.name

    @Field
    fun description(strategy: RecommendationStrategy): String = strategy.description

    @Field
    fun type(strategy: RecommendationStrategy): RecommendationStrategyType = strategy.type

    @Field
    fun status(strategy: RecommendationStrategy): RecommendationStrategyStatus = strategy.status

    @Field
    fun analyticsQueryId(strategy: RecommendationStrategy): UUID? = strategy.analyticsQueryId

    @Field
    fun configuration(strategy: RecommendationStrategy): JsonElement? = strategy.configuration

    @Field
    fun priority(strategy: RecommendationStrategy): Int = strategy.priority

    @Field
    fun maxRecommendations(strategy: RecommendationStrategy): Int = strategy.maxRecommendations

    @Field
    fun scheduledJobId(strategy: RecommendationStrategy): UUID? = strategy.scheduledJobId

    @Field
    fun lastEvaluated(strategy: RecommendationStrategy): OffsetDateTime? = strategy.lastEvaluated

    @Field
    fun created(strategy: RecommendationStrategy): OffsetDateTime = strategy.created

    @Field
    fun modified(strategy: RecommendationStrategy): OffsetDateTime = strategy.modified

    @Field
    suspend fun evaluationSchedule(authentication: AuthenticationContext, strategy: RecommendationStrategy): String? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val jobId = strategy.scheduledJobId ?: return null
        return schedulerService.getJob(jobId)?.cronExpression
    }
}
