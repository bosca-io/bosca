package bosca.search.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class InitializeJob(
    /* storage system id */
    @Contextual
    val id: UUID
) : IJobDefinition
