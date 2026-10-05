package bosca.ai.agents.graphql

import bosca.ai.agents.model.Agent
import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.service.AgentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController
class AgentController(
    private val agentService: AgentService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Agent> {

    @Field
    fun id(agent: Agent) = agent.id

    @Field
    fun key(agent: Agent) = agent.key

    @Field
    fun name(agent: Agent) = agent.name

    @Field
    fun description(agent: Agent) = agent.description

    @Field
    fun modelId(agent: Agent) = agent.modelId

    @Field
    fun promptId(agent: Agent) = agent.promptId

    @Field
    fun configuration(authentication: AuthenticationContext?, agent: Agent): JsonElement? {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return agent.configuration
    }

    @Field
    suspend fun subAgents(agent: Agent): List<Agent> {
        return agentService.getSubAgents(agent.id)
    }

    @Field
    suspend fun tools(agent: Agent): List<AgentTool> {
        return agentService.getTools(agent.id)
    }

    @Field
    fun gitRepositoryId(agent: Agent): UUID? = agent.gitRepositoryId

    @Field
    fun gitPath(agent: Agent): String? = agent.gitPath

    @Field
    fun lastSyncError(agent: Agent): String? = agent.lastSyncError
}
