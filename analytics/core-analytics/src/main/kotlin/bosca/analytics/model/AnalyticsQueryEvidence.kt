package bosca.analytics.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** SQL and normalized bindings captured with the execution that produced the accompanying rows. */
@Serializable
data class AnalyticsQueryEvidence(
    val queryId: UUID,
    val sql: String,
    val parameters: List<AnalyticsQueryBinding>,
    val executedAt: OffsetDateTime,
    val durationMs: Long,
)
