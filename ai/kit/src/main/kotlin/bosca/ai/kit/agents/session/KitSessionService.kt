package bosca.ai.kit.agents.session

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.kit.agents.KitResponse
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Records a Kit conversation's canonical user and assistant turns in chat history. Native ACP
 * session files and remote prompt reconciliation belong to the separate runtime lifecycle.
 */
interface KitSessionService : Service {

    /**
     * Record the user's own incoming [message] for [sessionId] as a chat-history message. Studio
     * re-renders purely from chat history, so the user's turn must be persisted (not just shown
     * optimistically) or it vanishes on the next sync. Pairs with [recordResponse] for Kit's reply.
     */
    suspend fun recordUserMessage(sessionId: UUID, message: ChatMessageInput)

    /**
     * Record Kit's final, user-facing [response] for [sessionId] as a chat-history message — the write
     * that makes Studio's UI re-render (it subscribes to the session's messages).
     */
    suspend fun recordResponse(sessionId: UUID, response: KitResponse)
}
