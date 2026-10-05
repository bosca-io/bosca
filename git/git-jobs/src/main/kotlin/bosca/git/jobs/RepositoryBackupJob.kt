package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryBackupJob(
    val repositoryId: UUID? = null
) : IJobDefinition
