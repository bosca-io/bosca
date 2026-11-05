package bosca.ai.kit.harness

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Optional knowledge selections in Agent.configuration.harnessContext. Conversation selections take priority. */
@Serializable
data class KitContextConfiguration(
    val metadataIds: List<@Contextual UUID> = emptyList(),
    val collectionIds: List<@Contextual UUID> = emptyList(),
    val memoryIds: List<@Contextual UUID> = emptyList(),
    val sources: List<KitContextSelection> = emptyList(),
)
