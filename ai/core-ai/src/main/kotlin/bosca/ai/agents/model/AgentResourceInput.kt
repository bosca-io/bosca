@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Create/update payload for an [AgentResource]. Exactly one implementation variant field
 * should be set; the application layer validates the XOR.
 */
@Serializable
data class AgentResourceInput(
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val configuration: JsonElement? = null,
    val staticText: String? = null,
    @Contextual
    val metadataId: Uuid? = null,
    @Contextual
    val documentMetadataId: Uuid? = null,
    val documentVersion: Int? = null,
    @Contextual
    val contentMetadataId: Uuid? = null,
    @Contextual
    val scriptId: Uuid? = null,
    val graphqlOperation: String? = null,
    val graphqlInputTransform: String? = null,
    val graphqlOutputTransform: String? = null,
    @Contextual
    val gitRepositoryId: Uuid? = null,
    val gitPath: String? = null
)
