package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Job payload for running garbage collection on a git repository's DFS
 * object store. Compacts loose objects into packfiles and updates
 * disk-size metrics. Dispatched by a weekly scheduled trigger, processing
 * repositories in order of staleness.
 */
@Serializable
data class RepositoryGcJob(
    val repositoryId: UUID
) : IJobDefinition
