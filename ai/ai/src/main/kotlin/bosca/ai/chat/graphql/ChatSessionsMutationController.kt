package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.db.transaction
import bosca.di.annotation.ProviderName
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ChatSessionsMutation

@TypeController
class ChatSessionsMutationController(
    private val service: ChatHistoryService,
    private val harness: bosca.ai.kit.harness.KitHarnessService,
    @ProviderName("kit-chat-dispatcher")
    private val chatDispatcher: ChatDispatcher
) : GraphQLController<ChatSessionsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, session: ChatSessionInput): ChatSession {
        verifyExecutionScope(authentication)
        val principalId = authentication.principal()?.id ?: throw SecurityException("Not authenticated")
        return transaction {
            service.createSession(principalId, session).also {
                if (it.agentKey == "kit") harness.activate(authentication, it.id)
            }
        }
    }

    @Field
    suspend fun updateTitle(authentication: AuthenticationContext, id: UUID, title: String): ChatSession {
        verifySessionOwnership(authentication, id)
        return service.updateSessionTitle(id, title)
    }

    @Field
    suspend fun clearHarnessSources(authentication: AuthenticationContext, sessionId: UUID): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.clearContext(sessionId)
        return true
    }

    @Field
    suspend fun addHarnessSource(authentication: AuthenticationContext, sessionId: UUID, source: bosca.ai.kit.harness.KitContextSelection): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.selectContext(sessionId, source, false)
        return true
    }

    @Field
    suspend fun removeHarnessSource(authentication: AuthenticationContext, sessionId: UUID, source: bosca.ai.kit.harness.KitContextSelection): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.selectContext(sessionId, source, true)
        return true
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
        chatDispatcher.dispatch(authenticationContext, sessionId, message)
        return true
    }

    @Field
    suspend fun decideHarnessPermission(authentication: AuthenticationContext, sessionId: UUID, approvalId: UUID, optionId: String): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.decide(authentication, sessionId, approvalId, optionId)
        return true
    }

    @Field
    suspend fun setHarnessSettings(authentication: AuthenticationContext, sessionId: UUID, choice: bosca.ai.kit.harness.KitHarnessChoice?): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.verifySettings(authentication, sessionId, choice)
        harness.setSettings(sessionId, choice)
        return true
    }

    @Field
    suspend fun activateHarness(authentication: AuthenticationContext, sessionId: UUID): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.activate(authentication, sessionId)
        return true
    }

    @Field
    suspend fun archiveHarness(authentication: AuthenticationContext, sessionId: UUID): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.retire(sessionId)
        return true
    }

    @Field
    suspend fun cancel(authentication: AuthenticationContext, sessionId: UUID): Boolean {
        verifySessionOwnership(authentication, sessionId)
        harness.cancel(sessionId)
        return true
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifySessionOwnership(authentication, id)
        transaction {
            harness.delete(id)
            service.deleteSession(id)
        }
        return true
    }

    private fun verifyExecutionScope(authentication: AuthenticationContext) {
        val principal = authentication.principal() ?: throw SecurityException("Not authenticated")
        if (principal is bosca.security.service.ScopedAuthenticatedPrincipal &&
            !principal.hasScope(bosca.security.service.ApiTokenScopes.AI_EXECUTE.name)) {
            throw SecurityException("AI conversation mutations require the ai:execute token scope")
        }
    }

    private suspend fun verifySessionOwnership(authentication: AuthenticationContext, sessionId: UUID) {
        verifyExecutionScope(authentication)
        val principalId = authentication.principal()?.id ?: throw SecurityException("Not authenticated")
        val session = service.getSession(sessionId) ?: throw SecurityException("Session not found")
        if (session.principalId != principalId) {
            throw SecurityException("Access denied")
        }
    }
}
