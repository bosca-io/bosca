package bosca.workops.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class SpecIndexJob(
    @Contextual val specId: UUID? = null,
    val deleteOnly: Boolean = false,
) : IJobDefinition
