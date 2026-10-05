package bosca.ai.kit.agents.image

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The image sub-agent's **structured** result: a human-facing [message] describing what it produced —
 * including the markdown image reference for any image it generated or edited — produced after its tool
 * loop runs.
 */
@Serializable
@LLMDescription("A summary of the image operation, including the markdown reference to any image produced.")
data class ImageResponse(
    @property:LLMDescription("A concise, user-facing summary of what was done, embedding the markdown image link (e.g. ![...](/api/v1/content/metadata/download?id=...)) when an image was produced, or the error if it failed.")
    val message: String,
)
