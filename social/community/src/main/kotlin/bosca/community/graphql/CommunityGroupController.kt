package bosca.community.graphql

import bosca.community.model.ChatChannel
import bosca.community.model.CommunityActivity
import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupMember
import bosca.community.model.CommunityGroupSignupToken
import bosca.community.model.Prayers
import bosca.community.model.PrayerStatus
import bosca.community.security.ChatChannelPermissionEvaluator
import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.service.ChatService
import bosca.community.service.CommunityActivityService
import bosca.community.service.CommunityService
import bosca.community.service.PrayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class CommunityGroupController(
    private val communityGroupPermissionEvaluator: CommunityGroupPermissionEvaluator,
    private val communityService: CommunityService,
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val communityActivityService: CommunityActivityService,
    private val prayerService: PrayerService,
) : GraphQLController<CommunityGroup> {

    @Field
    fun id(group: CommunityGroup) = group.id

    @Field
    fun name(group: CommunityGroup) = group.name

    @Field
    fun description(group: CommunityGroup) = group.description

    @Field
    fun type(group: CommunityGroup) = group.type

    @Field
    fun visibility(group: CommunityGroup) = group.visibility

    @Field
    fun attributes(group: CommunityGroup) = group.attributes

    @Field
    suspend fun members(authentication: AuthenticationContext, group: CommunityGroup): List<CommunityGroupMember> {
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.VIEW)
        return communityService.getMembers(group.id)
    }

    @Field
    suspend fun channels(authentication: AuthenticationContext, group: CommunityGroup): List<ChatChannel> {
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.VIEW)
        return chatChannelPermissionEvaluator.filterAllowed(
            authentication,
            chatService.getChannelsByGroupId(group.id),
            PermissionAction.VIEW
        )
    }

    @Field
    suspend fun activities(authentication: AuthenticationContext, group: CommunityGroup): List<CommunityActivity> {
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.VIEW)
        return communityActivityService.getActivities(group.id)
    }

    @Field
    suspend fun prayers(
        authentication: AuthenticationContext,
        group: CommunityGroup,
        status: List<PrayerStatus>? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Prayers {
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.VIEW)
        return prayerService.getRequests(group.id, status, limit, offset)
    }

    @Field
    suspend fun signupTokens(authentication: AuthenticationContext, group: CommunityGroup): List<CommunityGroupSignupToken> {
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        return communityService.getSignupTokens(group.id)
    }
}
