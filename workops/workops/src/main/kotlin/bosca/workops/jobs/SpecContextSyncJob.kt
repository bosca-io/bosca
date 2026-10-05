package bosca.workops.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class SpecContextSyncJob(
    @Contextual val specId: UUID? = null,
) : IJobDefinition
