package bosca.ai.agents.graphql

import bosca.ai.agents.model.AgentTool
import bosca.ai.agents.service.AgentToolService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object AgentTools

@TypeController
class AgentToolsController(
    private val service: AgentToolService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<AgentTools> {

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun all(authentication: AuthenticationContext): List<AgentTool> {
        verifyCanManage(authentication)
        return service.getAll()
    }

    @Field
    suspend fun tool(authentication: AuthenticationContext, id: UUID): AgentTool? {
        verifyCanManage(authentication)
        return service.get(id)
    }
}
