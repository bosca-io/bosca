package bosca.content.collection.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionDeleteFromIndexJob(
    val id: UUID? = null,
    val languageTag: String? = null,
    val storage: IndexStorageSystem? = null
) : IJobDefinition