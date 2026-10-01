package bosca.content.collection.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataParentItemCacheInvalidationJob(
    val id: UUID? = null,
    val version: Int? = null
) : IJobDefinition
