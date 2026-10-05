package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Defines a named recommendation generation strategy that controls how content candidates are
 * produced. Each strategy combines an algorithm [type] with an optional analytics query binding and
 * scheduled evaluation. Materialized strategies (trending, co-engagement) refresh a global
 * candidate pool on a schedule; the ML model strategy is served live per request. Personalization
 * (the learned ranker, dismissals, rating re-ranking) is applied at read time, so a strategy has no
 * per-profile targeting.
 */
@BatchKey("id")
@Serializable
data class RecommendationStrategy(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String = "",
    val type: RecommendationStrategyType,
    val status: RecommendationStrategyStatus = RecommendationStrategyStatus.DRAFT,
    @ColumnName("analytics_query_id")
    @Contextual
    val analyticsQueryId: UUID? = null,
    @Contextual
    val configuration: JsonElement? = null,
    val priority: Int = 0,
    @ColumnName("max_recommendations")
    val maxRecommendations: Int = 10,
    @ColumnName("scheduled_job_id")
    @Contextual
    val scheduledJobId: UUID? = null,
    @ColumnName("last_evaluated")
    @Contextual
    val lastEvaluated: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)
