package bosca.ai.memory.graphql

import bosca.ai.memory.MemoryService
import bosca.ai.memory.RememberMemoryInput
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class MemoriesMutationController(private val service: MemoryService, private val groups: GroupEvaluator) : GraphQLController<MemoriesMutation> {
    @Field suspend fun remember(authentication: AuthenticationContext, input: RememberMemoryInput): bosca.ai.memory.Memory {
        groups.verifyHasScope(authentication, "content:edit")
        return service.remember(authentication, input)
    }
    @Field suspend fun update(authentication: AuthenticationContext, id: UUID, expectedRevision: String, statement: String, refreshSource: Boolean = false, conversationId: UUID? = null): bosca.ai.memory.Memory {
        groups.verifyHasScope(authentication, "content:edit")
        conversationId?.let { service.reads(authentication, it) }
        return service.update(authentication, id, expectedRevision, statement, refreshSource).also { memory -> conversationId?.let { service.recordRead(authentication, it, memory) } }
    }
    @Field suspend fun forget(authentication: AuthenticationContext, id: UUID, expectedRevision: String): Boolean {
        groups.verifyHasScope(authentication, "content:delete")
        return service.forget(authentication, id, expectedRevision)
    }
}
