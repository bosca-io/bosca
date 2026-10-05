package bosca.ai.kit.agents.routing

import bosca.ai.chat.model.ChatMessageInput
import kotlinx.serialization.Serializable

/**
 * What the router looks at to decide a route. Today just the user's [message], but modeled as a
 * carrier so it can grow to include the conversation so far, the surface the request came from, or a
 * prior route that turned out wrong — the context a good routing decision actually depends on.
 */
@Serializable
data class RouteRequest(
    val message: ChatMessageInput,
)
