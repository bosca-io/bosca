@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.graphql

import bosca.ai.agents.model.AgentResource
import bosca.ai.agents.model.AgentResourceInput
import bosca.ai.agents.service.AgentResourceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

object AgentResourcesMutation

@TypeController
class AgentResourcesMutationController(
    private val service: AgentResourceService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AgentResourcesMutation> {

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canEdit = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canEdit) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun add(authentication: AuthenticationContext, resource: AgentResourceInput): AgentResource {
        verifyCanManage(authentication)
        return service.add(resource)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, resource: AgentResourceInput): AgentResource {
        verifyCanManage(authentication)
        return service.edit(id, resource)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        verifyCanManage(authentication)
        return service.delete(id)
    }

}
