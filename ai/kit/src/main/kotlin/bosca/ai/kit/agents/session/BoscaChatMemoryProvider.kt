package bosca.ai.kit.agents.session

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import bosca.serialization.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backs Koog's [ai.koog.agents.chatMemory.feature.ChatMemory] for a conversing Kit sub-agent — so its
 * prompt (system + the running conversation) carries across turns and Kit stops forgetting what was said.
 *
 * Keyed by the owning **chat session**, not the Koog `conversationId`: a sub-agent's run id is a
 * per-action UUID that changes every turn, so we resolve the stable chat session from the ambient
 * [KitSessionContext] (the planner's action sets it around the sub-agent run). Each conversing sub-agent
 * gets its OWN instance — RouteAgent's memory and ChatAgent's memory are separate prompt threads.
 *
 * In-memory for now: the conversation is durably stored in `ChatHistoryService` already, this is the
 * per-agent Koog-prompt cache. It survives across turns within a running instance; swap this for an
 * object-storage-backed provider when cross-restart / multi-instance durability is needed.
 */
class BoscaChatMemoryProvider(
    private val sessions: KitSessionService
) : ChatHistoryProvider {

    override suspend fun store(conversationId: String, messages: List<Message>) {
        val id = UUID.parse(conversationId)
        sessions.setMessages(id, messages)
    }

    override suspend fun load(conversationId: String): List<Message> {
        val id = UUID.parse(conversationId)
        val messages = sessions.getMessages(id)
        return messages
    }
}
