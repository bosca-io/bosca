package bosca.ai.chat.service

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.model.ChatSessionProcessing
import bosca.ai.chat.model.ChatSessionStatus
import bosca.ai.chat.model.ChatSessionStatuses
import bosca.ai.chat.repository.ChatHistoryRepository
import bosca.db.transaction
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

@ServiceImplementation
class ChatHistoryServiceImpl(
    private val repository: ChatHistoryRepository,
    private val pubsub: PubSubService
) : ChatHistoryService {

    override suspend fun getSessions(principalId: UUID): List<ChatSession> =
        repository.getSessionsByPrincipal(principalId)

    override suspend fun getSessions(principalId: UUID, agentKey: String): List<ChatSession> =
        repository.getSessionsByPrincipalAndAgent(principalId, agentKey)

    override suspend fun getSession(id: UUID): ChatSession? =
        repository.getSessionById(id)

    override suspend fun getSessionForUpdate(id: UUID): ChatSession? =
        repository.getSessionForUpdate(id)

    override suspend fun getChildSessions(parentSessionId: UUID): List<ChatSession> =
        repository.getChildSessions(parentSessionId)

    override suspend fun createSession(principalId: UUID, input: ChatSessionInput, id: UUID?): ChatSession =
        repository.createSession(id ?: UUID.random(), principalId, input.parentSessionId, input.agentKey, input.title, input.state)

    override suspend fun updateSessionTitle(id: UUID, title: String): ChatSession =
        repository.updateTitle(id, title)

    override suspend fun updateSessionState(id: UUID, state: JsonElement): ChatSession =
        repository.updateState(id, state)

    override suspend fun deleteSession(id: UUID) =
        repository.deleteSession(id)

    override suspend fun getMessages(sessionId: UUID): List<ChatHistoryMessage> {
        val messages = repository.getMessages(sessionId)
        return messages
    }

    override suspend fun setMessages(sessionId: UUID, messages: List<ChatHistoryMessage>) = transaction {
//        repository.deleteMessages(sessionId)
        messages.forEach { repository.addMessage(it) }
    }

    override suspend fun addMessage(message: ChatHistoryMessage): ChatHistoryMessage? {
        val result = repository.addMessage(message) ?: return null
        repository.touchSession(message.sessionId)
        pubsub.publish("bosca.ai.chat.v1.sessions.${message.sessionId}.messages", ChatHistoryMessage.serializer(), result)
        return result
    }

    override suspend fun dispatchStatus(sessionId: UUID, status: ChatSessionStatus) {
        repository.updateStatus(sessionId, status)
        pubsub.publish("bosca.ai.chat.v1.sessions.${sessionId}.statuses", ChatSessionStatuses.serializer(), ChatSessionStatuses(status))
    }

    override suspend fun setProcessing(sessionId: UUID, processing: Boolean) {
        repository.setProcessing(sessionId, processing)
        pubsub.publish("bosca.ai.chat.v1.sessions.${sessionId}.processing", ChatSessionProcessing.serializer(), ChatSessionProcessing(processing))
    }

    override fun subscribeToMessages(sessionId: UUID): Flow<ChatHistoryMessage> {
        return pubsub.subscribe("bosca.ai.chat.v1.sessions.$sessionId.messages", ChatHistoryMessage.serializer()).transform {
            emit(it.message)
        }
    }

    override fun subscribeToStatuses(sessionId: UUID): Flow<ChatSessionStatus> {
        return pubsub.subscribe("bosca.ai.chat.v1.sessions.$sessionId.statuses", ChatSessionStatuses.serializer()).transform {
            emit(it.message.status)
        }
    }

    override fun subscribeToProcessing(sessionId: UUID): Flow<Boolean> {
        return pubsub.subscribe("bosca.ai.chat.v1.sessions.$sessionId.processing", ChatSessionProcessing.serializer()).transform {
            emit(it.message.processing)
        }
    }

    override suspend fun touchSession(id: UUID) = repository.touchSession(id)
}
