package bosca.ai.kit.agents.description

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/** The [DescriptionAgent]'s structured result: a single concise meta [description]. */
@Serializable
@LLMDescription("A concise meta description summarizing the document.")
data class DescriptionResponse(
    @property:LLMDescription("A concise description of no more than 30 words, suitable for an HTML meta description.")
    val description: String,
)
