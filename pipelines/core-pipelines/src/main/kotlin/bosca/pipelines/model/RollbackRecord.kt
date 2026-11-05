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
 * One row in a run's saga-rollback log: a node that completed successfully
 * and declares a rollback pipeline, the [rollbackPipelineId] to run to undo it, and the node's [output]
 * to feed that pipeline. The DB-assigned `seq` orders completions; the run service replays in reverse.
 * [error] and [failedAt] are set while the rollback has failed and its side effects may remain in place.
 */
@Serializable
data class RollbackRecord(
    @ColumnName("run_id")
    @Contextual
    val runId: UUID,
    @ColumnName("node_id")
    val nodeId: String,
    @ColumnName("rollback_pipeline_id")
    @Contextual
    val rollbackPipelineId: UUID,
    @Contextual
    val output: JsonElement? = null,
    val seq: Long = 0,
    val error: String? = null,
    @ColumnName("failed_at")
    @Contextual
    val failedAt: OffsetDateTime? = null,
)
