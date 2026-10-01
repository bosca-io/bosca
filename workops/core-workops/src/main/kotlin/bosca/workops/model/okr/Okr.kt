package bosca.workops.model.okr

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ObjectiveState { DRAFT, ACTIVE, ACHIEVED, MISSED, CANCELLED }

@Serializable
enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

@Serializable
data class ObjectivePeriod(
    @Contextual val startDate: OffsetDateTime,
    @Contextual val endDate: OffsetDateTime,
    val name: String,
)

/**
 * R19 — every Key Result is one of four metric kinds. The
 * evaluator interprets each variant against its data source
 * (Bosca's saved filter for `TaskCompletion`, Trino for
 * `MetricEvent`, the row itself for the two scalar variants).
 */
@Serializable
sealed class KeyResultMetric {
    @Serializable @SerialName("TaskCompletion")
    data class TaskCompletion(@Contextual val savedFilterId: UUID) : KeyResultMetric()

    @Serializable @SerialName("MetricEvent")
    data class MetricEvent(
        val eventType: String,
        val aggregator: String,
    ) : KeyResultMetric()

    @Serializable @SerialName("Numeric")
    data class Numeric(val baseline: Double, val target: Double) : KeyResultMetric()

    @Serializable @SerialName("Boolean")
    data class Boolean(val achieved: kotlin.Boolean) : KeyResultMetric()

    @Serializable @SerialName("Percentage")
    data class Percentage(val target: Int) : KeyResultMetric()
}

@Serializable
data class Objective(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("portfolio_id")
    @Contextual
    val portfolioId: UUID? = null,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID? = null,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID? = null,
    val title: String,
    val description: String? = null,
    val state: ObjectiveState = ObjectiveState.ACTIVE,
    @ColumnName("period_start")
    @Contextual
    val periodStart: OffsetDateTime,
    @ColumnName("period_end")
    @Contextual
    val periodEnd: OffsetDateTime,
    @ColumnName("period_name")
    val periodName: String,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID,
    val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val version: Long = 0,
)

@Serializable
data class KeyResult(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("objective_id")
    @Contextual
    val objectiveId: UUID,
    val title: String,
    val description: String? = null,
    @ColumnName("metric_type")
    val metricType: String,
    /**
     * The decoded shape of [KeyResultMetric] keyed by `type`. The
     * evaluator decodes it on read.
     */
    @Contextual
    val metric: JsonElement = JsonObject(emptyMap()),
    @ColumnName("current_value")
    val currentValue: Double? = null,
    @ColumnName("computed_at")
    @Contextual
    val computedAt: OffsetDateTime? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val version: Long = 0,
)
