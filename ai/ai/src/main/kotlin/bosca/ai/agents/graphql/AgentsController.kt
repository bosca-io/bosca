package bosca.ai.agents.graphql

import bosca.ai.agents.model.Agent
import bosca.ai.agents.service.AgentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Agents

@TypeController
class AgentsController(
    private val service: AgentService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Agents> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Agent> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.getAll()
    }

    @Field
    suspend fun agent(authentication: AuthenticationContext, id: UUID): Agent? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.get(id)
    }

    @Field
    suspend fun agentByKey(authentication: AuthenticationContext, key: String): Agent? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.getByKey(key)
    }
}
