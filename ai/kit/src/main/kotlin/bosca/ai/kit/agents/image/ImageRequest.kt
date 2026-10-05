package bosca.ai.kit.agents.image

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/** The image sub-agent's request: the user's natural-language image [message] (generate or edit). */
@Serializable
data class ImageRequest(
    val message: ChatMessageInput,
)
