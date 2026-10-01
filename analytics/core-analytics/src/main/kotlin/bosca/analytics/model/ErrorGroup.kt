package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Aggregated state for a class of errors that share the same fingerprint.
 *
 * One row exists per [fingerprint] per app, accumulated server-side as
 * error events flow through the analytics pipeline. Mutable state
 * (count, last seen, status, assignee, AI summary) lives in Postgres
 * while the underlying error events themselves remain in the immutable
 * Iceberg event log.
 */
@Serializable
data class ErrorGroup(
    val fingerprint: String,
    @ColumnName("app_id")
    val appId: String,
    val type: String,
    val message: String,
    val fatal: Boolean,
    val status: ErrorGroupStatus,
    @Contextual
    @ColumnName("assignee_id")
    val assigneeId: UUID? = null,
    @ColumnName("first_seen")
    val firstSeen: OffsetDateTime,
    @ColumnName("last_seen")
    val lastSeen: OffsetDateTime,
    @ColumnName("event_count")
    val eventCount: Long,
    @ColumnName("sample_event_id")
    val sampleEventId: String? = null,
    @ColumnName("sample_stack")
    val sampleStack: String? = null,
    @ColumnName("ai_summary")
    val aiSummary: String? = null,
    @ColumnName("ai_summary_at")
    val aiSummaryAt: OffsetDateTime? = null,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
)
