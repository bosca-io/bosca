package bosca.ai.memory

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MemoryQuery(
    val text: String = "",
    @Contextual val collectionIds: List<UUID> = emptyList(),
    @Contextual val ids: List<UUID> = emptyList(),
    val tags: List<String> = emptyList(),
    val personal: Boolean = true,
    val offset: Long = 0,
    val limit: Int = 5,
)
