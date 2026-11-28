package bosca.ai.memory.graphql

import bosca.ai.memory.MemoryQuery
import bosca.ai.memory.MemoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class MemoriesController(private val service: MemoryService, private val groups: GroupEvaluator) : GraphQLController<Memories> {
    @Field suspend fun get(authentication: AuthenticationContext, id: UUID, conversationId: UUID? = null): bosca.ai.memory.Memory? {
        groups.verifyHasScope(authentication, "content:view")
        return service.get(authentication, id)?.also { memory -> conversationId?.let { service.recordRead(authentication, it, memory) } }
    }
    @Field suspend fun search(authentication: AuthenticationContext, query: MemoryQuery, conversationId: UUID? = null): List<bosca.ai.memory.Memory> {
        groups.verifyHasScope(authentication, "content:view")
        if (query.text.isNotBlank()) groups.verifyHasScope(authentication, "search:read")
        return service.search(authentication, query).also { memories ->
            conversationId?.let { id -> memories.forEach { service.recordRead(authentication, id, it) } }
        }
    }
}
