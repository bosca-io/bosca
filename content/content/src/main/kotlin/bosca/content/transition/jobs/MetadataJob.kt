package bosca.content.transition.jobs

import bosca.queue.annotations.IMetadataJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class MetadataJob(
    @Contextual
    override val id: UUID,
    override val version: Int?
) : IMetadataJobDefinition {
}