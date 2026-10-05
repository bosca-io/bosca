package bosca.ai.kit.agents.chat

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/** What the [ChatAgent] is asked to respond to: the user's chat [message]. */
@Serializable
data class ChatRequest(val message: ChatMessageInput)
