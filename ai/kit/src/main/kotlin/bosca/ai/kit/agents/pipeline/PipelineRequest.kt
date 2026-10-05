package bosca.ai.kit.agents.pipeline

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/** The pipeline specialist's request: the user's natural-language authoring or execution intent. */
@Serializable
data class PipelineRequest(val message: ChatMessageInput)
