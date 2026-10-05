package bosca.content.transition.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MetadataTransitionJob(
    val id: UUID,
    val version: Int
) : IJobDefinition