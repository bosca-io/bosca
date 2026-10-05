package bosca.ai.agents.graphql

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object McpServers

@TypeController
class McpServersController(
    private val service: McpServerRegistrationService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<McpServers> {

    private fun verifyCanManage(authentication: AuthenticationContext) {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            groupEvaluator.throwUnauthorized()
        }
    }

    @Field
    suspend fun all(authentication: AuthenticationContext): List<McpServerRegistration> {
        verifyCanManage(authentication)
        return service.getAll()
    }

    @Field
    suspend fun enabled(authentication: AuthenticationContext): List<McpServerRegistration> {
        verifyCanManage(authentication)
        return service.getAllEnabled()
    }

    @Field
    suspend fun server(authentication: AuthenticationContext, id: UUID): McpServerRegistration? {
        verifyCanManage(authentication)
        return service.get(id)
    }

    @Field
    suspend fun serverByKey(authentication: AuthenticationContext, key: String): McpServerRegistration? {
        verifyCanManage(authentication)
        return service.getByKey(key)
    }
}
