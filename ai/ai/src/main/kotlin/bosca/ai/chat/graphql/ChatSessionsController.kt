package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.di.annotation.ProviderName
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object ChatSessions

@TypeController
class ChatSessionsController(
    private val service: ChatHistoryService,
) : GraphQLController<ChatSessions> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<ChatSession> {
        val principalId = authentication.principal()?.id ?: error("Not authenticated")
        return service.getSessions(principalId)
    }

    @Field
    suspend fun session(authentication: AuthenticationContext, id: UUID): ChatSession? {
        val session = service.getSession(id) ?: return null
        if (session.principalId != authentication.principal()?.id) {
            return null
        }
        return session
    }
}
