package bosca.community.graphql

import bosca.community.model.CommunityGroup
import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.service.CommunityService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object Community

@TypeController
class CommunityController(
    private val communityService: CommunityService,
    private val communityGroupPermissionEvaluator: CommunityGroupPermissionEvaluator,
) : GraphQLController<Community> {

    @Field
    suspend fun group(authentication: AuthenticationContext?, id: UUID): CommunityGroup? {
        val group = communityService.getGroup(id) ?: return null
        if (!communityGroupPermissionEvaluator.isAllowed(authentication, group, PermissionAction.VIEW)) return null
        return group
    }

    @Field
    fun all(): CommunityGroups = CommunityGroups
}
