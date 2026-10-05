package bosca.ai.agents.graphql

import bosca.ai.agents.model.AgentResource
import bosca.ai.agents.service.AgentResourceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object AgentResources

@TypeController
class AgentResourcesController(
    private val service: AgentResourceService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<AgentResources> {

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun all(authentication: AuthenticationContext): List<AgentResource> {
        verifyCanManage(authentication)
        return service.getAll()
    }

    @Field
    suspend fun resource(authentication: AuthenticationContext, id: UUID): AgentResource? {
        verifyCanManage(authentication)
        return service.get(id)
    }
}
