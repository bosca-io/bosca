package bosca.workops.model.pipeline

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents a single CI/CD pipeline execution. Reported by the Bosca
 * CLI (`bosca version publish --ci`) or ingested via webhook from
 * GitHub Actions / TeamCity. Stages within a run are tracked
 * separately in [PipelineStageRun].
 */
@Serializable
data class PipelineRun(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("version_id")
    @Contextual
    val versionId: UUID? = null,
    @ColumnName("pipeline_id")
    val pipelineId: String,
    @ColumnName("pipeline_name")
    val pipelineName: String,
    @ColumnName("trigger_type")
    val triggerType: PipelineTriggerType,
    @ColumnName("trigger_ref")
    val triggerRef: String? = null,
    val status: PipelineStatus = PipelineStatus.PENDING,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("completed_at")
    @Contextual
    val completedAt: OffsetDateTime? = null,
    @ColumnName("external_url")
    val externalUrl: String? = null,
    val version: Long = 0,
)

@Serializable
data class PipelineStageRun(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("pipeline_run_id")
    @Contextual
    val pipelineRunId: UUID,
    @ColumnName("stage_name")
    val stageName: String,
    val status: PipelineStatus = PipelineStatus.PENDING,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime? = null,
    @ColumnName("completed_at")
    @Contextual
    val completedAt: OffsetDateTime? = null,
    @ColumnName("external_url")
    val externalUrl: String? = null,
)

@Serializable
enum class PipelineStatus { PENDING, RUNNING, PASSED, FAILED, CANCELLED, SKIPPED }

@Serializable
enum class PipelineTriggerType { PUSH, PR, TAG, MANUAL, WEBHOOK }

@Serializable
data class CreatePipelineRunInput(
    @Contextual
    val projectId: UUID,
    @Contextual
    val versionId: UUID? = null,
    val pipelineId: String,
    val pipelineName: String,
    val triggerType: PipelineTriggerType,
    val triggerRef: String? = null,
    val externalUrl: String? = null,
)
