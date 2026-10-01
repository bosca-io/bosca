package bosca.ai.agents.graphql

import bosca.ai.agents.model.AgentTool
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController
class AgentToolController(
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AgentTool> {

    @Field
    fun id(tool: AgentTool): UUID = tool.id

    @Field
    fun key(tool: AgentTool) = tool.key

    @Field
    fun name(tool: AgentTool) = tool.name

    @Field
    fun description(tool: AgentTool) = tool.description

    @Field
    fun configuration(authentication: AuthenticationContext?, tool: AgentTool): JsonElement? {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return tool.configuration
    }

    @Field
    fun scriptId(tool: AgentTool): UUID? = tool.scriptId

    @Field
    fun mcpServerId(tool: AgentTool): UUID? = tool.mcpServerId

    @Field
    fun graphqlOperation(tool: AgentTool): String? = tool.graphqlOperation

    @Field
    fun graphqlInputTransform(tool: AgentTool): String? = tool.graphqlInputTransform

    @Field
    fun graphqlOutputTransform(tool: AgentTool): String? = tool.graphqlOutputTransform

    @Field
    fun promptId(tool: AgentTool): UUID? = tool.promptId

    @Field
    fun modelId(tool: AgentTool): UUID? = tool.modelId

    @Field
    fun agentId(tool: AgentTool): UUID? = tool.agentId

    @Field
    fun gitRepositoryId(tool: AgentTool): UUID? = tool.gitRepositoryId

    @Field
    fun gitPath(tool: AgentTool): String? = tool.gitPath

    @Field
    fun lastSyncError(tool: AgentTool): String? = tool.lastSyncError
}
