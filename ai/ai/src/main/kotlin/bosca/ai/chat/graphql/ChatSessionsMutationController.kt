package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.cache.withRequestCache
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.annotation.ProviderName
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ChatSessionsMutation

@TypeController
class ChatSessionsMutationController(
    private val service: ChatHistoryService,
    @ProviderName("kit-chat-dispatcher")
    private val chatDispatcher: ChatDispatcher
) : GraphQLController<ChatSessionsMutation> {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Field
    suspend fun create(authentication: AuthenticationContext, session: ChatSessionInput): ChatSession {
        val principalId = authentication.principal()?.id ?: throw SecurityException("Not authenticated")
        return service.createSession(principalId, session)
    }

    @Field
    suspend fun updateTitle(authentication: AuthenticationContext, id: UUID, title: String): ChatSession {
        verifySessionOwnership(authentication, id)
        return service.updateSessionTitle(id, title)
    }

    @Field
    suspend fun addAttachment(authentication: AuthenticationContext, id: UUID, metadataId: UUID): ChatSession {
        return addToStateArray(authentication, id, "attachments", metadataId)
    }

    @Field
    suspend fun removeAttachment(authentication: AuthenticationContext, id: UUID, metadataId: UUID): ChatSession {
        return removeFromStateArray(authentication, id, "attachments", metadataId)
    }

    @Field
    suspend fun addCollectionAttachment(authentication: AuthenticationContext, id: UUID, collectionId: UUID): ChatSession {
        return addToStateArray(authentication, id, "collectionAttachments", collectionId)
    }

    @Field
    suspend fun removeCollectionAttachment(authentication: AuthenticationContext, id: UUID, collectionId: UUID): ChatSession {
        return removeFromStateArray(authentication, id, "collectionAttachments", collectionId)
    }

    private suspend fun addToStateArray(authentication: AuthenticationContext, id: UUID, key: String, valueId: UUID): ChatSession {
        verifySessionOwnership(authentication, id)
        return transaction {
            val session = service.getSessionForUpdate(id) ?: throw SecurityException("Session not found")
            val state = (session.state as? JsonObject) ?: JsonObject(emptyMap())
            val existing = (state[key] as? JsonArray)?.toMutableList() ?: mutableListOf()
            val valueStr = JsonPrimitive(valueId.toString())
            if (valueStr !in existing) {
                existing.add(valueStr)
            }
            val newState = JsonObject(state.toMutableMap().apply {
                put(key, JsonArray(existing))
            })
            service.updateSessionState(id, newState)
        }
    }

    private suspend fun removeFromStateArray(authentication: AuthenticationContext, id: UUID, key: String, valueId: UUID): ChatSession {
        verifySessionOwnership(authentication, id)
        return transaction {
            val session = service.getSessionForUpdate(id) ?: throw SecurityException("Session not found")
            val state = (session.state as? JsonObject) ?: JsonObject(emptyMap())
            val existing = (state[key] as? JsonArray)?.toMutableList() ?: mutableListOf()
            val valueStr = JsonPrimitive(valueId.toString())
            existing.remove(valueStr)
            val newState = JsonObject(state.toMutableMap().apply {
                put(key, JsonArray(existing))
            })
            service.updateSessionState(id, newState)
        }
    }

    @Field
    suspend fun send(authenticationContext: AuthenticationContext, sessionId: UUID, message: ChatMessageInput): Boolean {
        verifySessionOwnership(authenticationContext, sessionId)
        service.setProcessing(sessionId, true)
        scope.launch {
            try {
                withConnectionManager {
                    withRequestCache {
                        chatDispatcher.dispatch(authenticationContext, sessionId, message)
                    }
                }
            } catch (e: Exception) {
                try {
                    withConnectionManager { service.setProcessing(sessionId, false) }
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifySessionOwnership(authentication, id)
        service.deleteSession(id)
        return true
    }

    private suspend fun verifySessionOwnership(authentication: AuthenticationContext, sessionId: UUID) {
        val principalId = authentication.principal()?.id ?: throw SecurityException("Not authenticated")
        val session = service.getSession(sessionId) ?: throw SecurityException("Session not found")
        if (session.principalId != principalId) {
            throw SecurityException("Access denied")
        }
    }
}
