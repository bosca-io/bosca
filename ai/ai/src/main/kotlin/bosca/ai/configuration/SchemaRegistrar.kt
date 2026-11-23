package bosca.ai.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {
    /** Context kinds shared by conversation selection and managed delegation. */
    @Schema("kit_context.graphqls")
    val kitContext: String

    @Schema("delegations.graphqls")
    val delegations: String

    @Schema("memories.graphqls")
    val memories: String

    @Schema("agents.graphqls")
    val agents: String

    @Schema("agent_resources.graphqls")
    val agentResources: String

    @Schema("chat.graphqls")
    val chat: String

    @Schema("mcp_servers.graphqls")
    val mcpServers: String

    @Schema("agent_repo.graphqls")
    val agentRepo: String

    @Schema("ai_namespace.graphqls")
    val aiNamespace: String

    @Schema("models.graphqls")
    val models: String

    @Schema("prompts.graphqls")
    val prompts: String
}
