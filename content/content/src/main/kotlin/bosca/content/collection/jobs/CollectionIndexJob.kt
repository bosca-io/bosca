package bosca.content.collection.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionIndexJob(
    val id: UUID? = null,
    val storage: IndexStorageSystem? = null,
    val deleteFirst: Boolean = false,
    val deleteOnly: Boolean = false,
    val batchSize: Int? = null
) : IJobDefinition
