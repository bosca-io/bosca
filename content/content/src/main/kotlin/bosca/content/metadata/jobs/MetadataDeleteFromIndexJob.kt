package bosca.content.metadata.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataDeleteFromIndexJob(
    val id: UUID? = null,
    val version: Int? = null,
    val storage: IndexStorageSystem? = null
) : IJobDefinition