package bosca.community.graphql

import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.service.CommunityService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object CommunityGroups

@TypeController
class CommunityGroupsController(
    private val communityGroupPermissionEvaluator: CommunityGroupPermissionEvaluator,
    private val communityService: CommunityService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<CommunityGroups> {

    @Field
    suspend fun groups(authentication: AuthenticationContext, offset: Long, limit: Int) = communityGroupPermissionEvaluator.filterAllowed(
        authentication,
        communityService.getGroups(limit, offset),
        PermissionAction.VIEW
    )

    @Field
    suspend fun total(authentication: AuthenticationContext): Long {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return communityService.getGroupCount()
    }
}