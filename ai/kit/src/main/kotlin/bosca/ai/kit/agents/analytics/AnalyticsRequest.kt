package bosca.ai.kit.agents.analytics

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/** The SQL sub-agent's request: a natural-language analytics [question] to answer with data. */
@Serializable
data class AnalyticsRequest(
    val question: ChatMessageInput,
)
