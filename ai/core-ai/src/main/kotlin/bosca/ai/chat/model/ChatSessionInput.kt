@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.chat.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class ChatSessionInput(
    val agentKey: String,
    val title: String = "",
    val state: JsonElement = JsonObject(emptyMap()),
    /** When set, the created session hangs off of this parent chat session. */
    @Contextual
    val parentSessionId: Uuid? = null
)
