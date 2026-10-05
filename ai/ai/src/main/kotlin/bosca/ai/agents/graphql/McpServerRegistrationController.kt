package bosca.ai.agents.graphql

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpTransportType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@TypeController
class McpServerRegistrationController(
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<McpServerRegistration> {

    @Field
    fun id(server: McpServerRegistration): UUID = server.id

    @Field
    fun key(server: McpServerRegistration) = server.key

    @Field
    fun name(server: McpServerRegistration) = server.name

    @Field
    fun description(server: McpServerRegistration) = server.description

    @Field
    fun transportType(server: McpServerRegistration): McpTransportType = server.transportType

    @Field
    fun configuration(authentication: AuthenticationContext?, server: McpServerRegistration): JsonElement? {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return server.configuration
    }

    @Field
    fun enabled(server: McpServerRegistration) = server.enabled

    @Field
    fun gitRepositoryId(server: McpServerRegistration): UUID? = server.gitRepositoryId

    @Field
    fun gitPath(server: McpServerRegistration): String? = server.gitPath

    @Field
    fun lastSyncError(server: McpServerRegistration): String? = server.lastSyncError
}
