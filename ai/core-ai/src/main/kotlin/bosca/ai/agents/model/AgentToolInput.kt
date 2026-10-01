@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class AgentToolInput(
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    val scriptId: Uuid? = null,
    @Contextual
    val mcpServerId: Uuid? = null,
    val graphqlOperation: String? = null,
    val graphqlInputTransform: String? = null,
    val graphqlOutputTransform: String? = null,
    @Contextual
    val promptId: Uuid? = null,
    @Contextual
    val modelId: Uuid? = null,
    @Contextual
    val agentId: Uuid? = null,
    @Contextual
    val gitRepositoryId: Uuid? = null,
    val gitPath: String? = null
)
