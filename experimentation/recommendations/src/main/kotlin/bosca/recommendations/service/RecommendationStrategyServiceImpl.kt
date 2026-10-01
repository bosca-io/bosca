package bosca.recommendations.service

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.db.transaction
import bosca.recommendations.jobs.EvaluateStrategyJob
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.model.CoEngagement
import bosca.recommendations.model.CohortCoEngagement
import bosca.recommendations.repository.RecommendationRepository
import bosca.recommendations.repository.RecommendationStrategyRepository
import bosca.recommendations.repository.CoEngagementRepository
import bosca.recommendations.repository.CohortCoEngagementRepository
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

@ServiceImplementation
class RecommendationStrategyServiceImpl(
    private val strategyRepository: RecommendationStrategyRepository,
    private val recommendationRepository: RecommendationRepository,
    private val coEngagementRepository: CoEngagementRepository,
    private val cohortCoEngagementRepository: CohortCoEngagementRepository,
    private val analyticsQueryExecutionService: AnalyticsQueryExecutionService,
    private val schedulerService: SchedulerService,
    private val recommendationService: RecommendationService,
    private val json: Json,
) : RecommendationStrategyService {

    override suspend fun getAll(offset: Long, limit: Int): List<RecommendationStrategy> {
        return strategyRepository.getAll(offset, limit)
    }

    override suspend fun getById(id: UUID): RecommendationStrategy? {
        return strategyRepository.getById(id)
    }

    override suspend fun getByIds(ids: List<UUID>): List<RecommendationStrategy> {
        if (ids.isEmpty()) return emptyList()
        return strategyRepository.getByIds(ids)
    }

    override suspend fun add(input: RecommendationStrategyInput): RecommendationStrategy = transaction {
        val strategy = RecommendationStrategy(
            name = input.name,
            description = input.description,
            type = input.type,
            status = input.status,
            analyticsQueryId = input.analyticsQueryId,
            configuration = input.configuration,
            priority = input.priority,
            maxRecommendations = input.maxRecommendations,
        )
        val created = strategyRepository.add(strategy)
        val schedule = input.evaluationSchedule
        if (schedule != null) {
            val jobId = createScheduledJob(created.id, created.name, schedule)
            strategyRepository.updateScheduledJobId(created.id, jobId)
        } else {
            created
        }
    }

    override suspend fun edit(id: UUID, input: RecommendationStrategyInput): RecommendationStrategy = transaction {
        val existing = strategyRepository.getById(id)
            ?: throw NoSuchElementException("Strategy not found: $id")
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            type = input.type,
            status = input.status,
            analyticsQueryId = input.analyticsQueryId,
            configuration = input.configuration,
            priority = input.priority,
            maxRecommendations = input.maxRecommendations,
        )
        val result = strategyRepository.update(updated)
        val jobId = syncScheduledJob(id, input.name, existing.scheduledJobId, input.evaluationSchedule)
        val saved = if (jobId != existing.scheduledJobId) {
            strategyRepository.updateScheduledJobId(id, jobId)
        } else {
            result
        }
        if (existing.type == RecommendationStrategyType.PERSONALIZED || input.type == RecommendationStrategyType.PERSONALIZED) {
            recommendationService.invalidatePersonalizedFeeds()
        }
        saved
    }

    override suspend fun delete(id: UUID) = transaction {
        val existing = strategyRepository.getById(id)
        val existingJobId = existing?.scheduledJobId
        if (existingJobId != null) {
            schedulerService.deleteJob(existingJobId)
        }
        recommendationRepository.deleteByStrategyId(id)
        strategyRepository.deleteById(id)
    }

    override suspend fun evaluate(strategyId: UUID): RecommendationStrategy {
        val strategy = strategyRepository.getById(strategyId)
            ?: throw NoSuchElementException("Strategy not found: $strategyId")
        when (strategy.type) {
            RecommendationStrategyType.TRENDING -> evaluateGlobal(strategy, requireAnalyticsQuery(strategy))
            RecommendationStrategyType.CO_ENGAGEMENT -> evaluateCoEngagements(strategy, requireAnalyticsQuery(strategy))
            RecommendationStrategyType.COHORT_CO_ENGAGEMENT -> evaluateCohortCoEngagements(strategy, requireAnalyticsQuery(strategy))
            // The ML model strategy is served live per request (retrieved + ranked on demand and cached),
            // so there is nothing to materialize on evaluation.
            RecommendationStrategyType.PERSONALIZED -> log.info(
                "ML model strategy {} is served live per request; evaluate is a no-op.", strategy.name,
            )
        }
        return strategyRepository.updateLastEvaluated(strategyId, OffsetDateTime.now())
    }

    private fun requireAnalyticsQuery(strategy: RecommendationStrategy): UUID {
        return strategy.analyticsQueryId
            ?: throw IllegalStateException("Strategy has no analytics query binding: ${strategy.id}")
    }

    /**
     * Evaluates a strategy that produces global recommendations (e.g., trending).
     * The analytics query returns metadata_id/collection_id and score without profile targeting.
     */
    private suspend fun evaluateGlobal(strategy: RecommendationStrategy, queryId: UUID) {
        log.info("Evaluating global strategy: {} ({})", strategy.name, strategy.id)
        recommendationRepository.deleteByStrategyId(strategy.id)
        val response = analyticsQueryExecutionService.execute(queryId, emptyList())
        val recommendations = extractRecommendations(response, strategy)
        recommendations.forEach { upsertRecommendation(it) }
        log.info("Strategy {} generated {} recommendations", strategy.name, recommendations.size)
    }

    /**
     * Extracts recommendation candidates from analytics query results.
     * Expects rows with either `metadata_id` or `collection_id` (at least one required),
     * plus optional `score` (defaults to 0.0) and `reason` columns.
     */
    private fun extractRecommendations(
        response: AnalyticsQueryResponse,
        strategy: RecommendationStrategy,
    ): List<Recommendation> {
        return response.records.mapNotNull { row ->
            try {
                val obj = row.jsonObject
                val metadataId = (obj["metadata_id"] as? JsonPrimitive)?.let { UUID.parse(it.content) }
                val collectionId = (obj["collection_id"] as? JsonPrimitive)?.let { UUID.parse(it.content) }
                if (metadataId == null && collectionId == null) return@mapNotNull null
                val score = (obj["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val reason = (obj["reason"] as? JsonPrimitive)?.content
                Recommendation(
                    metadataId = metadataId,
                    collectionId = collectionId,
                    strategyId = strategy.id,
                    score = score,
                    reason = reason,
                )
            } catch (e: Exception) {
                log.warn("Failed to parse recommendation row: {}", e.message)
                null
            }
        }
    }

    private suspend fun upsertRecommendation(recommendation: Recommendation) {
        if (recommendation.metadataId != null) {
            recommendationRepository.upsertMetadata(recommendation)
        } else if (recommendation.collectionId != null) {
            recommendationRepository.upsertCollection(recommendation)
        }
    }

    /**
     * Evaluates a CO_ENGAGEMENT (item-to-item) strategy. Runs the analytics query of co-occurrence
     * edges and replaces this strategy's `co_engagements` mapping. The query returns one row per edge
     * with `source_id` + `co_engaged_id` (+ optional `score`, `reason`); the result is item-anchored, not
     * profile-targeted.
     */
    private suspend fun evaluateCoEngagements(strategy: RecommendationStrategy, queryId: UUID) {
        log.info("Evaluating co-engagement strategy: {} ({})", strategy.name, strategy.id)
        coEngagementRepository.deleteByStrategyId(strategy.id)
        var offset = 0
        var generated = 0
        while (true) {
            val response = analyticsQueryExecutionService.execute(
                queryId,
                listOf(
                    AnalyticsQueryExecutionParameterInput("offset", JsonPrimitive(offset)),
                    AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(CO_ENGAGEMENT_PAGE_SIZE)),
                ),
            )
            val items = extractCoEngagements(response, strategy)
            items.forEach { coEngagementRepository.upsert(it) }
            generated += items.size
            val recordsRead = response.records.size
            if (recordsRead < CO_ENGAGEMENT_PAGE_SIZE) break
            offset += recordsRead
        }
        log.info("Strategy {} generated {} co-engagement edges", strategy.name, generated)
    }

    /** A parsed co-occurrence edge from an analytics row: source + co-engaged ids and optional score/reason. */
    private data class ParsedEdge(val sourceId: UUID, val coEngagedId: UUID, val score: Double, val reason: String?)

    /**
     * Parses the co-occurrence fields shared by both co-engagement shapes: `source_id` + `co_engaged_id`
     * (both required, UUID strings) and optional `score` (defaults to 0.0) + `reason`. Returns null for a
     * missing id or a self-edge (source == co-engaged). A malformed id throws (the caller logs + skips).
     */
    private fun parseEdge(obj: JsonObject): ParsedEdge? {
        val sourceId = (obj["source_id"] as? JsonPrimitive)?.let { UUID.parse(it.content) } ?: return null
        val coEngagedId = (obj["co_engaged_id"] as? JsonPrimitive)?.let { UUID.parse(it.content) } ?: return null
        if (sourceId == coEngagedId) return null
        val score = (obj["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val reason = (obj["reason"] as? JsonPrimitive)?.content
        return ParsedEdge(sourceId, coEngagedId, score, reason)
    }

    /**
     * Extracts co-engagement edges from analytics query results (rows with `source_id` + `co_engaged_id`,
     * optional `score`/`reason`; self-edges and malformed rows skipped).
     */
    private fun extractCoEngagements(response: AnalyticsQueryResponse, strategy: RecommendationStrategy): List<CoEngagement> {
        return response.records.mapNotNull { row ->
            try {
                val edge = parseEdge(row.jsonObject) ?: return@mapNotNull null
                CoEngagement(
                    sourceMetadataId = edge.sourceId,
                    coEngagedMetadataId = edge.coEngagedId,
                    strategyId = strategy.id,
                    score = edge.score,
                    reason = edge.reason,
                )
            } catch (e: Exception) {
                log.warn("Failed to parse co-engagement row: {}", e.message)
                null
            }
        }
    }

    /**
     * Evaluates a COHORT_CO_ENGAGEMENT ("people like you") strategy. Runs the analytics query of per-cohort
     * co-occurrence edges and replaces this strategy's `cohort_co_engagements` mapping. The query returns one
     * row per edge with `cohort_key` + `source_id` + `co_engaged_id` (+ optional `score`, `reason`).
     */
    private suspend fun evaluateCohortCoEngagements(strategy: RecommendationStrategy, queryId: UUID) {
        log.info("Evaluating cohort co-engagement strategy: {} ({})", strategy.name, strategy.id)
        cohortCoEngagementRepository.deleteByStrategyId(strategy.id)
        // The per-user and per-source caps that bound the nightly co-occurrence batch live in the strategy's
        // configuration (default 200/200), so they are tunable via the API without re-seeding the query. A
        // strategy seeded before this existed carries no configuration, so fall back to the defaults.
        val configJson = strategy.configuration
        val config = if (configJson != null) {
            json.decodeFromJsonElement(CohortCoEngagementConfiguration.serializer(), configJson)
        } else {
            CohortCoEngagementConfiguration()
        }
        val parameters = listOf(
            AnalyticsQueryExecutionParameterInput("basketCap", JsonPrimitive(config.perUserItemCap)),
            AnalyticsQueryExecutionParameterInput("sourceCap", JsonPrimitive(config.perSourceCap)),
        )
        val response = analyticsQueryExecutionService.execute(queryId, parameters)
        val items = extractCohortCoEngagements(response, strategy)
        items.forEach { cohortCoEngagementRepository.upsert(it) }
        log.info("Strategy {} generated {} cohort co-engagement edges", strategy.name, items.size)
    }

    /**
     * Extracts cohort co-engagement edges from analytics query results. Expects rows with a non-blank
     * `cohort_key` and `source_id` + `co_engaged_id` (both required, UUID strings), plus optional `score`
     * (defaults to 0.0) and `reason`. Self-edges (source == co-engaged) and cohort-less rows are skipped.
     */
    private fun extractCohortCoEngagements(response: AnalyticsQueryResponse, strategy: RecommendationStrategy): List<CohortCoEngagement> {
        return response.records.mapNotNull { row ->
            try {
                val obj = row.jsonObject
                val cohortKey = (obj["cohort_key"] as? JsonPrimitive)?.let { it.content.takeIf(String::isNotBlank) } ?: return@mapNotNull null
                val edge = parseEdge(obj) ?: return@mapNotNull null
                CohortCoEngagement(
                    sourceMetadataId = edge.sourceId,
                    cohortKey = cohortKey,
                    coEngagedMetadataId = edge.coEngagedId,
                    strategyId = strategy.id,
                    score = edge.score,
                    reason = edge.reason,
                )
            } catch (e: Exception) {
                log.warn("Failed to parse cohort co-engagement row: {}", e.message)
                null
            }
        }
    }

    private suspend fun createScheduledJob(strategyId: UUID, strategyName: String, cronExpression: String): UUID {
        val job = schedulerService.createJob(
            input = ScheduledJobInput(
                name = "Evaluate recommendation strategy: $strategyName",
                jobName = "evaluate-strategy",
                jobParameters = json.encodeToJsonElement(EvaluateStrategyJob(strategyId)),
                cronExpression = cronExpression,
                enabled = true,
                allowConcurrent = false,
                catchUp = false,
                maxCatchUp = 1
            ),
            createdBy = UUID.NIL
        )
        return job.id
    }

    private suspend fun syncScheduledJob(
        strategyId: UUID,
        strategyName: String,
        existingJobId: UUID?,
        evaluationSchedule: String?,
    ): UUID? {
        if (evaluationSchedule == null) {
            if (existingJobId != null) {
                schedulerService.deleteJob(existingJobId)
            }
            return null
        }
        return if (existingJobId != null) {
            schedulerService.updateJob(
                id = existingJobId,
                input = ScheduledJobInput(
                    name = "Evaluate recommendation strategy: $strategyName",
                    jobName = "evaluate-strategy",
                    jobParameters = json.encodeToJsonElement(EvaluateStrategyJob(strategyId)),
                    cronExpression = evaluationSchedule,
                    enabled = true,
                    allowConcurrent = false,
                    catchUp = false,
                    maxCatchUp = 1
                )
            )
            existingJobId
        } else {
            createScheduledJob(strategyId, strategyName, evaluationSchedule)
        }
    }

    companion object {
        private const val CO_ENGAGEMENT_PAGE_SIZE = 5_000
        private val log = LoggerFactory.getLogger(RecommendationStrategyServiceImpl::class.java)
    }
}
