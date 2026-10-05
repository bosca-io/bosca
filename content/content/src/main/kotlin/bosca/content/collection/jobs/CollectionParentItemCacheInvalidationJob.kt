package bosca.content.collection.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionParentItemCacheInvalidationJob(
    val id: UUID? = null
) : IJobDefinition
