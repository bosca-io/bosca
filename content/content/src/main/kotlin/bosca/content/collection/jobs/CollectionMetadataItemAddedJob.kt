package bosca.content.collection.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionMetadataItemAddedJob(
    override val id: UUID? = null,
    override val metadataId: UUID? = null
) : IJobDefinition, CollectionMetadataItemSyncJob