package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionLanguageVariantMetadataRelationshipRemovedJob(
    val id: UUID? = null,
    val relationship: CollectionLanguageVariantMetadataRelationship?
) : IJobDefinition
