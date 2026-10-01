package bosca.content.metadata.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class DocumentClearCollaborationJob(
    val id: UUID? = null,
    val version: Int? = null,
) : IJobDefinition