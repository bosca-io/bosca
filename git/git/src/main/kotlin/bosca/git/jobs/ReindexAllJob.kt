package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import kotlinx.serialization.Serializable

@Serializable
data class ReindexAllJob(
    val storage: IndexStorageSystem? = null
) : IJobDefinition
