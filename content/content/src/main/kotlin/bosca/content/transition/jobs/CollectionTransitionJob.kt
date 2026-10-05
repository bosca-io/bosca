package bosca.content.transition.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionTransitionJob(
    val id: UUID,
    val languageTag: String? = null,
) : IJobDefinition