package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Job payload for purging soft-deleted repositories that have exceeded
 * the retention period (default 30 days). Dispatched by a daily
 * scheduled trigger.
 */
@Serializable
data class RepositoryPurgeJob(
    val repositoryId: UUID? = null
) : IJobDefinition
