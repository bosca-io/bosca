package bosca.content.metadata.jobs

import bosca.content.model.ContentRelationship
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataRelationshipAddedJob(
    override val id: UUID? = null,
    override val relationship: ContentRelationship?
) : IJobDefinition, MetadataSyncJob