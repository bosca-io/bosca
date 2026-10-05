package bosca.ai.kit.agents.topics

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/** A single topic the agent matched, echoed from the available topics list by [id] and [name]. */
@Serializable
@LLMDescription("A topic matched to the document, taken verbatim from the available topics list.")
data class TopicMatch(
    @property:LLMDescription("The matched topic's id, exactly as given in the available topics list.")
    val id: String,
    @property:LLMDescription("The matched topic's name.")
    val name: String,
)

/** The [TopicsAgent]'s structured result: the available topics relevant to the document. */
@Serializable
@LLMDescription("The topics from the available list that are relevant to the document.")
data class TopicsResponse(
    @property:LLMDescription("The relevant topics; empty if none of the available topics apply.")
    val topics: List<TopicMatch> = emptyList(),
)
