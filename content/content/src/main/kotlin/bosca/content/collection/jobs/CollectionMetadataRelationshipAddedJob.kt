package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionMetadataRelationshipAddedJob(
    val id: UUID? = null,
    val relationship: CollectionMetadataRelationship?
) : IJobDefinition