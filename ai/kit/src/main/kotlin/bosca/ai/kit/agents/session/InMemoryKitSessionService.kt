package bosca.ai.kit.agents.session

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.serialization.JSONElement
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.kit.agents.KitResponse
import bosca.serialization.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [KitSessionService] — checkpoints and recorded responses live in maps. Used by tests and
 * for ephemeral sessions that don't need to survive a restart; the durable [KitSessionServiceImpl]
 * (object storage + the chat session) is the production implementation.
 */
class InMemoryKitSessionService : KitSessionService {

    private val mutex = Mutex()
    private val sessions = mutableMapOf<UUID, List<AgentCheckpointData>>()
    private val parentToRuns = mutableMapOf<UUID, MutableSet<UUID>>()
    private val responses = mutableMapOf<UUID, List<KitResponse>>()
    private val userMessages = mutableMapOf<UUID, List<ChatMessageInput>>()
    private val conversation = mutableMapOf<UUID, List<Message>>()
    private val storage = mutableMapOf<UUID, Map<String, JSONElement>>()

    // ChatMemory's conversation cache: store() replaces the session's messages, load() returns them.
    // The durable impl reconstructs these from chat-history rows; the in-memory double just holds them.
    override suspend fun getMessages(sessionId: UUID): List<Message> =
        mutex.withLock { conversation[sessionId] ?: emptyList() }

    override suspend fun setMessages(sessionId: UUID, messages: List<Message>) {
        mutex.withLock { conversation[sessionId] = messages }
    }

    override suspend fun saveCheckpoint(sessionId: UUID, checkpoint: AgentCheckpointData) {
        val parentSessionId = currentCoroutineContext()[KitSessionContext]?.parentSessionId
        mutex.withLock {
            sessions[sessionId] = (sessions[sessionId] ?: emptyList()) + checkpoint
            parentSessionId?.let {
                parentToRuns.getOrPut(parentSessionId) { mutableSetOf() }.add(sessionId)
            }
        }
    }

    override suspend fun getCheckpoints(sessionId: UUID): List<AgentCheckpointData> =
        mutex.withLock { sessions[sessionId] ?: emptyList() }

    override suspend fun getLatestCheckpoint(sessionId: UUID): AgentCheckpointData? =
        mutex.withLock { sessions[sessionId]?.maxByOrNull { it.version } }

    override suspend fun clearSession(parentSessionId: UUID) {
        mutex.withLock { parentToRuns.remove(parentSessionId)?.forEach { sessions.remove(it) } }
    }

    override suspend fun recordUserMessage(sessionId: UUID, message: ChatMessageInput) {
        mutex.withLock { userMessages[sessionId] = (userMessages[sessionId] ?: emptyList()) + message }
    }

    override suspend fun recordResponse(sessionId: UUID, response: KitResponse) {
        mutex.withLock { responses[sessionId] = (responses[sessionId] ?: emptyList()) + response }
    }

    // Per-session agent storage, persisted across runs: the planner restores it on start and saves it on
    // completion, so per-action state (e.g. each sub-agent's stable run id) survives the tombstone reset.
    override suspend fun getStorage(sessionId: UUID): Map<String, JSONElement> =
        mutex.withLock { storage[sessionId] ?: emptyMap() }

    override suspend fun setStorage(sessionId: UUID, storage: Map<String, JSONElement>) {
        mutex.withLock { this.storage[sessionId] = storage }
    }

    /** User messages recorded for [sessionId], in order — for asserting the user's turn was persisted. */
    suspend fun getRecordedUserMessages(sessionId: UUID): List<ChatMessageInput> =
        mutex.withLock { userMessages[sessionId] ?: emptyList() }

    /** Responses recorded for [sessionId], in order — for asserting the EventHandler fired. */
    suspend fun getRecordedResponses(sessionId: UUID): List<KitResponse> =
        mutex.withLock { responses[sessionId] ?: emptyList() }
}
