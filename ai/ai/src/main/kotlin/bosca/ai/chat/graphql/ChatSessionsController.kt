package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.ApiTokenScopes
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object ChatSessions

@TypeController
class ChatSessionsController(
    private val service: ChatHistoryService,
    private val groups: GroupEvaluator,
) : GraphQLController<ChatSessions> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<ChatSession> {
        groups.verifyHasScope(authentication, ApiTokenScopes.AI_EXECUTE.name)
        val principalId = authentication.principal()?.id ?: error("Not authenticated")
        return service.getSessions(principalId)
    }

    @Field
    suspend fun session(authentication: AuthenticationContext, id: UUID): ChatSession? {
        groups.verifyHasScope(authentication, ApiTokenScopes.AI_EXECUTE.name)
        val session = service.getSession(id) ?: return null
        if (session.principalId != authentication.principal()?.id) {
            return null
        }
        return session
    }
}
