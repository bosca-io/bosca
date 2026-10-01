package bosca.content.metadata.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataProcessContentJob(
    val id: UUID,
    val version: Int
) : IJobDefinition