package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryIndexJob(
    val storage: IndexStorageSystem? = null,
    val repositoryId: UUID? = null,
    val deleteOnly: Boolean = false
) : IJobDefinition
