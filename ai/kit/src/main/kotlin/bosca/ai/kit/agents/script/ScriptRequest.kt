package bosca.ai.kit.agents.script

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/** The script sub-agent's request: the user's natural-language scripting [message] to carry out. */
@Serializable
data class ScriptRequest(
    val message: ChatMessageInput,
)
