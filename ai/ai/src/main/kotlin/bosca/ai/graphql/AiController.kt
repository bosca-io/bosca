package bosca.ai.graphql

import bosca.ai.agents.graphql.AgentResources
import bosca.ai.agents.graphql.AgentTools
import bosca.ai.agents.graphql.Agents
import bosca.ai.models.graphql.Models
import bosca.ai.prompt.graphql.Prompts
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Marker for the `ai` query namespace. Lets clients query everything in the AI domain
 * through a single top-level field: `query { ai { agents { ... } prompts { ... } ... } }`.
 *
 * Each `@Field` below is a passthrough to an existing namespace object — the actual
 * resolvers live in `AgentsController`, `PromptsController`, etc. unchanged.
 */
object Ai

@TypeController
class AiController : GraphQLController<Ai> {

    @Field
    fun agents() = Agents

    @Field
    fun agentTools() = AgentTools

    @Field
    fun agentResources() = AgentResources

    @Field
    fun mcp() = AiMcp

    @Field
    fun models() = Models

    @Field
    fun prompts() = Prompts
}
