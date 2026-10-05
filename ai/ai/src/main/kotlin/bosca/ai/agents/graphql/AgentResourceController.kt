package bosca.ai.agents.graphql

import bosca.ai.agents.model.AgentResource
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * GraphQL field resolvers for the [AgentResource] type. Bosca's GraphQL is schema-first with a
 * default data fetcher that throws, so every field declared in the SDL needs an explicit resolver
 * here — this controller covers the full `AgentResource` type.
 */
@TypeController
class AgentResourceController(
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<AgentResource> {

    @Field
    fun id(resource: AgentResource): UUID = resource.id

    @Field
    fun key(resource: AgentResource) = resource.key

    @Field
    fun name(resource: AgentResource) = resource.name

    @Field
    fun description(resource: AgentResource) = resource.description

    @Field
    fun configuration(authentication: AuthenticationContext?, resource: AgentResource): JsonElement? {
        val canView = groupEvaluator.hasGroup(authentication, "agent.manager") || groupEvaluator.hasAdminGroup(authentication)
        if (!canView) {
            return null
        }
        return resource.configuration
    }

    @Field
    fun staticText(resource: AgentResource): String? = resource.staticText

    @Field
    fun metadataId(resource: AgentResource): UUID? = resource.metadataId

    @Field
    fun documentMetadataId(resource: AgentResource): UUID? = resource.documentMetadataId

    @Field
    fun documentVersion(resource: AgentResource): Int? = resource.documentVersion

    @Field
    fun contentMetadataId(resource: AgentResource): UUID? = resource.contentMetadataId

    @Field
    fun scriptId(resource: AgentResource): UUID? = resource.scriptId

    @Field
    fun graphqlOperation(resource: AgentResource): String? = resource.graphqlOperation

    @Field
    fun graphqlInputTransform(resource: AgentResource): String? = resource.graphqlInputTransform

    @Field
    fun graphqlOutputTransform(resource: AgentResource): String? = resource.graphqlOutputTransform

    @Field
    fun gitRepositoryId(resource: AgentResource): UUID? = resource.gitRepositoryId

    @Field
    fun gitPath(resource: AgentResource): String? = resource.gitPath

    @Field
    fun lastSyncError(resource: AgentResource): String? = resource.lastSyncError
}
