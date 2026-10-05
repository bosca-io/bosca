package bosca.content.collection.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class AutoAssignCollectionsJob(
    val id: UUID,
    val version: Int? = null,
) : IJobDefinition
