package bosca.ai.kit.agents.graphql

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/** The GraphQL sub-agent's request: the user's natural-language [message] to fulfil via the GraphQL API. */
@Serializable
data class GraphQLRequest(
    val message: ChatMessageInput,
)
