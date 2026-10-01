package bosca.git.model

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** Git-specific parameters for a system-scheduler job that fires one pipeline's cron trigger. */
@Serializable
data class PipelineScheduleJob(val pipelineId: UUID) : IJobDefinition {
    companion object {
        const val NAME = "pipeline-schedule"
    }
}
