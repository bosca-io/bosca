@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/** A run-log row joined with its pipeline's name, for run-history listings (the GraphQL `PipelineHistoryRun`). */
@Serializable
data class PipelineRunLogWithName(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("pipeline_id")
    val pipelineId: UUID,
    /** The durable run this history row came from, or null for inline manual/API runs. */
    @ColumnName("run_id")
    @Contextual
    val runId: UUID? = null,
    @ColumnName("pipeline_name")
    val pipelineName: String,
    @ColumnName("event_name")
    val eventName: String,
    /** Terminal outcome (always OK/FAILED/CANCELLED), read from the text `outcome` column via [PipelineRunStatusMapper]. */
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
