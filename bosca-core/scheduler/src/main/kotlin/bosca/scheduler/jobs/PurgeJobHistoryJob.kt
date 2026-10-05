package bosca.scheduler.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Periodic maintenance request that removes completed scheduler history older than
 * [retentionDays].
 */
@Serializable
data class PurgeJobHistoryJob(
    val retentionDays: Int = 30,
) : IJobDefinition {
    init {
        require(retentionDays > 0) { "retentionDays must be greater than zero" }
    }
}
