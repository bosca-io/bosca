package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlin.uuid.Uuid

/** Uses the CLI's existing scoped GraphQL transport for shared Bosca memory. */
class MemoryApi(network: NetworkClient, private val conversationId: Uuid? = null) : Api(network) {
    suspend fun remember(input: RememberMemoryInput): IMemoryFragment = network.boscaGraphql.execute(
        RememberMemory, RememberMemory.Variables(input.copy(conversationId = conversationId ?: input.conversationId)),
    ).ai.memories.remember

    suspend fun get(id: Uuid): IMemoryFragment? = network.boscaGraphql.execute(
        GetMemory, GetMemory.Variables(id, conversationId),
    ).ai.memories.get

    suspend fun search(query: MemoryQuery): List<IMemoryFragment> = network.boscaGraphql.execute(
        SearchMemories, SearchMemories.Variables(query, conversationId),
    ).ai.memories.search

    suspend fun update(id: Uuid, revision: String, statement: String, refreshSource: Boolean): IMemoryFragment = network.boscaGraphql.execute(
        UpdateMemory, UpdateMemory.Variables(id, revision, statement, refreshSource, conversationId),
    ).ai.memories.update

    suspend fun forget(id: Uuid, revision: String): Boolean = network.boscaGraphql.execute(
        ForgetMemory, ForgetMemory.Variables(id, revision),
    ).ai.memories.forget
}
