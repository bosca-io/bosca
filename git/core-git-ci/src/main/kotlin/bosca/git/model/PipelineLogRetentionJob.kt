package bosca.git.model

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Deletes pipeline logs older than the configured retention period
 * from object storage. Runs daily via the scheduler.
 */
@Serializable
data class PipelineLogRetentionJob(
    val retentionDays: Int = 31
) : IJobDefinition
