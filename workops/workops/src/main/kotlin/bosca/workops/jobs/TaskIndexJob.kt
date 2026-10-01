package bosca.workops.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class TaskIndexJob(
    @Contextual val taskId: UUID? = null,
    @Contextual val projectId: UUID? = null,
    val deleteOnly: Boolean = false,
) : IJobDefinition
