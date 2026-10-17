package bosca.git.model

import bosca.events.annotation.JobEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Emitted when a pipeline run changes status. Used by the UI for
 * real-time updates and by downstream systems for notifications.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.git.pipeline")
@Serializable
data class PipelineEvent(
    override val repositoryId: UUID,
    val pipelineRunId: UUID,
    val pipelineId: UUID,
    val status: PipelineRunStatus,
    /** Persisted source modification time identifying the run revision that emitted this event. */
    val modified: OffsetDateTime,
) : GitEvent() {
    override fun identityKey(): Any = pipelineRunId
}
