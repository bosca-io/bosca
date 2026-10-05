package bosca.ai.chat.repository

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.model.ChatSessionStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface ChatHistoryRepository {

    @Query("select * from ai.chat_sessions where principal_id = :principalId and parent_session_id is null order by modified desc limit :limit offset :offset")
    suspend fun getSessionsByPrincipal(principalId: UUID, limit: Int = 200, offset: Long = 0): List<ChatSession>

    @Query("select * from ai.chat_sessions where principal_id = :principalId and agent_key = :agentKey and parent_session_id is null order by modified desc limit :limit offset :offset")
    suspend fun getSessionsByPrincipalAndAgent(principalId: UUID, agentKey: String, limit: Int = 200, offset: Long = 0): List<ChatSession>

    @Query("select * from ai.chat_sessions where id = :id")
    suspend fun getSessionById(id: UUID): ChatSession?

    @Query("select * from ai.chat_sessions where id = :id for update")
    suspend fun getSessionForUpdate(id: UUID): ChatSession?

    @Query("select * from ai.chat_sessions where parent_session_id = :parentSessionId order by modified desc limit :limit offset :offset")
    suspend fun getChildSessions(parentSessionId: UUID, limit: Int = 200, offset: Long = 0): List<ChatSession>

    @Query("insert into ai.chat_sessions (id, principal_id, parent_session_id, agent_key, title, state) values (:id, :principalId, :parentSessionId, :agentKey, :title, :state) returning *")
    suspend fun createSession(id: UUID, principalId: UUID, parentSessionId: UUID?, agentKey: String, title: String, state: JsonElement): ChatSession

    @Query("update ai.chat_sessions set title = :title, modified = now() where id = :id returning *")
    suspend fun updateTitle(id: UUID, title: String): ChatSession

    @Query("update ai.chat_sessions set state = :state, modified = now() where id = :id returning *")
    suspend fun updateState(id: UUID, state: JsonElement): ChatSession

    @Query("update ai.chat_sessions set status = :status::ai.chat_session_status, modified = now() where id = :id")
    suspend fun updateStatus(id: UUID, status: ChatSessionStatus)

    @Query("update ai.chat_sessions set processing = :processing, modified = now() where id = :id")
    suspend fun setProcessing(id: UUID, processing: Boolean)

    @Query("update ai.chat_sessions set modified = now() where id = :id")
    suspend fun touchSession(id: UUID)

    @Query("delete from ai.chat_sessions where id = :id")
    suspend fun deleteSession(id: UUID)

    @Query("select * from ai.chat_messages where session_id = :sessionId order by created asc")
    suspend fun getMessages(sessionId: UUID): List<ChatHistoryMessage>

    @Query("delete from ai.chat_messages where session_id = :sessionId")
    suspend fun deleteMessages(sessionId: UUID)

    @Query("insert into ai.chat_messages (id, session_id, author, content, event_data) values (:id, :sessionId, :author, :content, :event) on conflict do nothing returning *")
    suspend fun addMessage(message: ChatHistoryMessage): ChatHistoryMessage?
}
