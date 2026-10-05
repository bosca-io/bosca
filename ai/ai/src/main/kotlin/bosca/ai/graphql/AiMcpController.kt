package bosca.ai.graphql

import bosca.ai.agents.graphql.McpServers
import bosca.ai.agents.graphql.McpServersMutation
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Marker for the `ai.mcp` query namespace — `servers` is the inbound surface (external MCP servers Bosca consumes). */
object AiMcp

@TypeController
class AiMcpController : GraphQLController<AiMcp> {

    @Field
    fun servers() = McpServers
}

/** Marker for the `ai.mcp` mutation namespace. */
object AiMcpMutation

@TypeController
class AiMcpMutationController : GraphQLController<AiMcpMutation> {

    @Field
    fun servers() = McpServersMutation
}
