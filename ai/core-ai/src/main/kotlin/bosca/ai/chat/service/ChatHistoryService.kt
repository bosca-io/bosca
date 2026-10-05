package bosca.ai.chat.service

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.model.ChatSessionStatus
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * Service for persisting and retrieving chat sessions and their message history,
 * as well as providing real-time subscription capabilities for streaming updates.
 *
 * A [ChatSession] groups a sequence of [ChatHistoryMessage] records under a specific
 * principal (user) and agent. Sessions track metadata such as title, custom state,
 * and timestamps. This service also supports pub/sub patterns for message and
 * status streaming, enabling real-time chat experiences.
 */
interface ChatHistoryService : Service {

    /**
     * Retrieves all chat sessions belonging to the specified principal, across all agents.
     *
     * @param principalId the unique identifier of the user whose sessions to retrieve
     * @return the list of sessions owned by the principal
     */
    suspend fun getSessions(principalId: UUID): List<ChatSession>

    /**
     * Retrieves chat sessions belonging to the specified principal and filtered
     * by agent key, allowing retrieval of sessions for a specific agent only.
     *
     * @param principalId the unique identifier of the user whose sessions to retrieve
     * @param agentKey the key of the agent to filter sessions by
     * @return the list of matching sessions
     */
    suspend fun getSessions(principalId: UUID, agentKey: String): List<ChatSession>

    /**
     * Retrieves a single chat session by its unique identifier.
     *
     * @param id the session's unique identifier
     * @return the matching session, or `null` if no session exists with the given [id]
     */
    suspend fun getSession(id: UUID): ChatSession?

    /**
     * Retrieves a single chat session by its unique identifier, acquiring a lock
     * suitable for subsequent mutation. Use this variant when the session will be
     * modified as part of the current operation to ensure consistency.
     *
     * @param id the session's unique identifier
     * @return the matching session, or `null` if no session exists with the given [id]
     */
    suspend fun getSessionForUpdate(id: UUID): ChatSession?

    /**
     * Retrieves the chat sessions that hang off of the given parent session, newest first.
     *
     * @param parentSessionId the parent session's unique identifier
     * @return the child sessions, or an empty list if the parent has none
     */
    suspend fun getChildSessions(parentSessionId: UUID): List<ChatSession>

    /**
     * Creates and persists a new chat session for the specified principal.
     *
     * @param principalId the unique identifier of the user who owns this session
     * @param input the session definition including agent key, optional title, initial state, and
     * optional [ChatSessionInput.parentSessionId] linking it to a parent session
     * @return the newly created session with its generated identifier and timestamps
     */
    suspend fun createSession(principalId: UUID, input: ChatSessionInput, id: UUID? = null): ChatSession

    /**
     * Updates the display title of an existing chat session.
     *
     * @param id the unique identifier of the session to update
     * @param title the new title for the session
     * @return the updated session reflecting the new title
     */
    suspend fun updateSessionTitle(id: UUID, title: String): ChatSession

    /**
     * Updates the custom state JSON associated with a chat session. The state
     * can hold arbitrary application-specific data such as conversation context
     * or agent memory.
     *
     * @param id the unique identifier of the session to update
     * @param state the new state as a JSON element
     * @return the updated session reflecting the new state
     */
    suspend fun updateSessionState(id: UUID, state: JsonElement): ChatSession

    /**
     * Permanently removes a chat session and its associated message history.
     *
     * @param id the unique identifier of the session to delete
     */
    suspend fun deleteSession(id: UUID)

    /**
     * Retrieves all messages belonging to the specified chat session, in order.
     *
     * @param sessionId the unique identifier of the chat session
     * @return the ordered list of messages in the session
     */
    suspend fun getMessages(sessionId: UUID): List<ChatHistoryMessage>

    suspend fun setMessages(sessionId: UUID, messages: List<ChatHistoryMessage>)

    /**
     * Persists a new message to the chat history and notifies any active subscribers.
     *
     * @param message the message to persist, including session reference, author, content, and event data
     * @return the persisted message with its generated identifier and timestamp
     */
    suspend fun addMessage(message: ChatHistoryMessage): ChatHistoryMessage?

    /**
     * Updates the modified timestamp of a session to the current time,
     * indicating recent activity without changing any other session fields.
     *
     * @param id the unique identifier of the session to touch
     */
    suspend fun touchSession(id: UUID)

    /**
     * Broadcasts a status update for the specified session to any active status subscribers.
     * This is used to signal transitions such as streaming start, completion, or failure.
     *
     * @param sessionId the unique identifier of the chat session
     * @param status the new status to dispatch (STREAMING, COMPLETED, or FAILED)
     */
    suspend fun dispatchStatus(sessionId: UUID, status: ChatSessionStatus)

    /**
     * Sets the processing flag for the specified session, indicating whether the
     * dispatcher is actively working on it. This flag is true for the entire
     * dispatcher lifecycle, encompassing both the preparation phase and streaming.
     *
     * @param sessionId the unique identifier of the chat session
     * @param processing true if the dispatcher is actively working, false otherwise
     */
    suspend fun setProcessing(sessionId: UUID, processing: Boolean)

    /**
     * Returns a [Flow] that emits new [ChatHistoryMessage] instances as they are
     * added to the specified session. Useful for real-time streaming of chat responses.
     *
     * @param sessionId the unique identifier of the chat session to subscribe to
     * @return a flow of messages added to the session after subscription
     */
    fun subscribeToMessages(sessionId: UUID): Flow<ChatHistoryMessage>

    /**
     * Returns a [Flow] that emits [ChatSessionStatus] updates for the specified session.
     * Useful for notifying clients when streaming begins, completes, or fails.
     *
     * @param sessionId the unique identifier of the chat session to subscribe to
     * @return a flow of status updates for the session
     */
    fun subscribeToStatuses(sessionId: UUID): Flow<ChatSessionStatus>

    /**
     * Returns a [Flow] that emits processing state changes for the specified session.
     * Emits true when the dispatcher starts working on the session, and false when
     * it finishes (whether successfully or with an error).
     *
     * @param sessionId the unique identifier of the chat session to subscribe to
     * @return a flow of boolean processing state updates
     */
    fun subscribeToProcessing(sessionId: UUID): Flow<Boolean>
}
