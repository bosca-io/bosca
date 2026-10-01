@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * One finished pipeline run, recorded in the append-only run *history*
 * (`pipelines.pipeline_run_log`) — distinct from [PipelineRun], the live, mutable run-state row.
 * Each triggered/manual/API run appends exactly one of these on completion.
 */
@Serializable
data class PipelineRunLog(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("pipeline_id")
    val pipelineId: UUID,
    /** The durable run this history row came from, or null for inline manual/API runs (no run row). */
    @ColumnName("run_id")
    @Contextual
    val runId: UUID? = null,
    @ColumnName("event_name")
    val eventName: String,
    /** Terminal outcome of the run (always OK/FAILED/CANCELLED). Stored in the text `outcome` column via [PipelineRunStatusMapper]. */
    @DbMapper(PipelineRunStatusMapper::class)
    val outcome: PipelineRunStatus,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime,
    @ColumnName("finished_at")
    @Contextual
    val finishedAt: OffsetDateTime? = null,
    @ColumnName("duration_ms")
    val durationMs: Long? = null,
    @ColumnName("error_message")
    val errorMessage: String? = null,
)
