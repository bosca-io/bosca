@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.ai.agents.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class McpServerRegistrationInput(
    val key: String,
    val name: String,
    val description: String = "",
    val transportType: McpTransportType,
    @Contextual
    val configuration: JsonElement,
    val enabled: Boolean = true,
    @Contextual
    val gitRepositoryId: kotlin.uuid.Uuid? = null,
    val gitPath: String? = null
)
