package bosca.profile.profile.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ProfileIndexJob(
    val deleteFirst: Boolean = false,
    val deleteOnly: Boolean = false,
    val storage: IndexStorageSystem? = null,
    val id: UUID? = null,
    val batchSize: Int? = null
) : IJobDefinition
