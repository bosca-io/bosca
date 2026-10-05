@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * One node-execution event in a durable run's timeline — the persisted,
 * append-only record behind run observability (the `pipelines.pipeline_run_node` table). The executor
 * emits these as it runs nodes (and the run service on resume); the run-detail query and
 * per-node metrics read them back.
 *
 *  - [status] — the node's outcome ([NodeExecutionStatus]); a node may have multiple events
 *    (e.g. SUSPENDED then OK) so the timeline shows its full lifecycle.
 *  - [port] — the output port the value left on (a routing/error port), or `null` for the implicit output.
 *  - [output] — a size-bounded JSON snapshot of the node's output (`null` when none, skipped, or capped).
 *  - [error] — the failure message when [status] is FAILED.
 *  - [durationMs] — finished − started, in milliseconds.
 *
 * Append-only: there is no version/soft-delete here. Retention/archival is handled in bulk.
 */
@Serializable
data class NodeExecutionRecord(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("run_id")
    @Contextual
    val runId: UUID,
    @ColumnName("node_id")
    val nodeId: String,
    val status: NodeExecutionStatus,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime,
    @ColumnName("finished_at")
    @Contextual
    val finishedAt: OffsetDateTime,
    @ColumnName("duration_ms")
    val durationMs: Long,
    val port: String? = null,
    val error: String? = null,
    @Contextual
    val output: JsonElement? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = java.time.OffsetDateTime.now(),
)
