package bosca.ai.graphql

import bosca.ai.agents.git.graphql.AgentRepoMutation
import bosca.ai.agents.graphql.AgentResourcesMutation
import bosca.ai.agents.graphql.AgentToolsMutation
import bosca.ai.agents.graphql.AgentsMutation
import bosca.ai.models.graphql.ModelsMutation
import bosca.ai.prompt.graphql.PromptsMutation
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Marker for the `ai` mutation namespace. Lets clients write everything in the AI domain
 * through a single top-level field: `mutation { ai { agents { add(...) } ... } }`.
 *
 * Includes `agentRepo` for AGENT_PROJECT Git linkage/backfill — that surface only
 * makes sense inside the AI namespace, so it has no top-level alias.
 */
object AiMutation

@TypeController
class AiMutationController : GraphQLController<AiMutation> {

    @Field
    fun agents() = AgentsMutation

    @Field
    fun agentTools() = AgentToolsMutation

    @Field
    fun agentResources() = AgentResourcesMutation

    @Field
    fun mcp() = AiMcpMutation

    @Field
    fun models() = ModelsMutation

    @Field
    fun prompts() = PromptsMutation

    @Field
    fun agentRepo() = AgentRepoMutation
}
