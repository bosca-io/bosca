package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A single execution of a pipeline, created when a trigger fires or a
 * manual run is requested. Tracks the overall status and timing of
 * all jobs within the run.
 */
@Serializable
data class PipelineRun(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("pipeline_id") val pipelineId: UUID,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("commit_sha") val commitSha: String,
    val ref: String,
    @ColumnName("trigger_type") val triggerType: PipelineTriggerType,
    @Contextual @ColumnName("triggered_by") val triggeredBy: UUID? = null,
    val status: PipelineRunStatus = PipelineRunStatus.QUEUED,
    val number: Int = 0,
    @ColumnName("concurrency_group") val concurrencyGroup: String? = null,
    /** The effective trigger parameters (submitted + declared-input defaults), keyed by their dotted
     *  names (`release.version`, `promotion.environment`, `inputs.*`) — what the dashboard's plan
     *  view and promotion-chain validation read back. */
    val parameters: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val started: OffsetDateTime? = null,
    @Contextual val finished: OffsetDateTime? = null
)
