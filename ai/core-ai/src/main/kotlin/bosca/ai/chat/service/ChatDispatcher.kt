package bosca.ai.chat.service

import bosca.ai.chat.model.ChatMessageInput
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall

/**
 * Responsible for dispatching incoming chat messages to the appropriate agent
 * and streaming responses back to the client over the active HTTP connection.
 *
 * Implementations manage the lifecycle of dispatched requests, including
 * agent selection, message processing, and response streaming via the [ServerCall].
 */
interface ChatDispatcher {

    /**
     * @param authenticationContext the security context of the authenticated user initiating the message
     * @param sessionId the unique identifier of the chat session this message belongs to
     * @param message the incoming chat message containing the user's role and content parts
     */
    suspend fun dispatch(
        authenticationContext: AuthenticationContext,
        sessionId: UUID,
        message: ChatMessageInput
    )
}