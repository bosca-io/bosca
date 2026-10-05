package bosca.ai.agents.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class AgentInput(
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val modelId: Uuid,
    @Contextual
    val promptId: Uuid,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    val gitRepositoryId: Uuid? = null,
    val gitPath: String? = null
)
