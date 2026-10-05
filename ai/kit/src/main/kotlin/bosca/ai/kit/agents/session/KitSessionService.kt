package bosca.ai.kit.agents.session

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.serialization.JSONElement
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.kit.agents.KitResponse
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Stores and retrieves a Kit run's checkpoints — the planner/sub-agent snapshots that make a run
 * resumable — keyed by `sessionId` (the run's own id). A Bosca [Service]: the durable
 * `@ServiceImplementation KitSessionServiceImpl` writes the (large) checkpoint bytes to object storage
 * and a small index row (grouped under the owning chat session); [InMemoryKitSessionService] backs
 * tests and ephemeral use.
 *
 * A `checkpoint` is Koog's opaque [AgentCheckpointData] (`@Serializable`); this service just stores
 * and returns it. The Koog [BoscaPersistenceStorageProvider] adapts this to the framework's
 * `PersistenceStorageProvider` (its `sessionId` is the Koog run id = our [sessionId]).
 */
interface KitSessionService : Service {

    suspend fun getMessages(sessionId: UUID): List<Message>

    suspend fun setMessages(sessionId: UUID, messages: List<Message>)

    /** Append [checkpoint] to the run identified by [sessionId] (parent planner run, or a sub-agent run). */
    suspend fun saveCheckpoint(sessionId: UUID, checkpoint: AgentCheckpointData)

    /** All checkpoints for the run [sessionId], oldest first. */
    suspend fun getCheckpoints(sessionId: UUID): List<AgentCheckpointData>

    /** The most recent checkpoint for the run [sessionId], or null if it has none. */
    suspend fun getLatestCheckpoint(sessionId: UUID): AgentCheckpointData?

    /**
     * Discard every checkpoint owned by the chat session [parentSessionId] — its parent run and all its
     * sub-agent runs (bytes and index). Called after a turn completes successfully: the checkpoints only
     * existed for mid-turn crash-resume, so they're dead weight once the turn is done.
     */
    suspend fun clearSession(parentSessionId: UUID)

    /**
     * Record the user's own incoming [message] for [sessionId] as a chat-history message. Studio
     * re-renders purely from chat history, so the user's turn must be persisted (not just shown
     * optimistically) or it vanishes on the next sync. Pairs with [recordResponse] for Kit's reply.
     */
    suspend fun recordUserMessage(sessionId: UUID, message: ChatMessageInput)

    /**
     * Record Kit's final, user-facing [response] for [sessionId] as a chat-history message — the write
     * that makes Studio's UI re-render (it subscribes to the session's messages). Only the resolved
     * response the user should see is recorded here; internal sub-agent/tool-call turns are not.
     */
    suspend fun recordResponse(sessionId: UUID, response: KitResponse)

    suspend fun getStorage(sessionId: UUID): Map<String, JSONElement>

    suspend fun setStorage(sessionId: UUID, storage: Map<String, JSONElement>)
}
