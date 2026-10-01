package bosca.ai.kit.agents.topics

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** One candidate topic the [TopicsAgent] may match against — its collection [id] and display [name]. */
@Serializable
data class AvailableTopic(
    @Contextual val id: UUID,
    val name: String,
)
