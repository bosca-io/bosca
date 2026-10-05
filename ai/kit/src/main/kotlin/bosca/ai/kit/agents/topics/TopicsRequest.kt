package bosca.ai.kit.agents.topics

import kotlinx.serialization.Serializable

/**
 * What the [TopicsAgent] matches: the document's plain [text] against the [availableTopics] it must
 * choose from. The candidate list is gathered by the owning action (collections tagged `type = "Topic"`),
 * not by the agent — the agent only matches.
 */
@Serializable
data class TopicsRequest(
    val text: String,
    val availableTopics: List<AvailableTopic>,
)
