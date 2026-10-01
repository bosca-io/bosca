package bosca.community.graphql

import bosca.community.model.ChatChannel
import bosca.community.model.CommunityGroup
import bosca.community.model.Prayers
import bosca.community.model.PrayerStatus
import bosca.community.security.ChatChannelPermissionEvaluator
import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.service.ChatService
import bosca.community.service.CommunityService
import bosca.community.service.PrayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.graphql.ProfileCommunity
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

@TypeController
class ProfileCommunityController(
    private val communityService: CommunityService,
    private val communityGroupPermissionEvaluator: CommunityGroupPermissionEvaluator,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val prayerService: PrayerService,
) : GraphQLController<ProfileCommunity> {

    @Field
    suspend fun groups(authentication: AuthenticationContext, community: ProfileCommunity): List<CommunityGroup> {
        profilePermissionEvaluator.verifyAllowed(authentication, community.profile, PermissionAction.LIST)
        return communityGroupPermissionEvaluator.filterAllowed(
            authentication,
            communityService.getGroups(community.profile.id),
            PermissionAction.VIEW
        )
    }

    @Field
    suspend fun channels(authentication: AuthenticationContext, community: ProfileCommunity): List<ChatChannel> {
        profilePermissionEvaluator.verifyAllowed(authentication, community.profile, PermissionAction.LIST)
        return chatChannelPermissionEvaluator.filterAllowed(
            authentication,
            chatService.getChannels(community.profile.id),
            PermissionAction.VIEW
        )
    }

    @Field
    suspend fun prayers(
        authentication: AuthenticationContext,
        community: ProfileCommunity,
        groupIds: List<UUID>? = null,
        status: List<PrayerStatus>? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Prayers {
        profilePermissionEvaluator.verifyAllowed(authentication, community.profile, PermissionAction.LIST)
        return prayerService.getFeed(community.profile.id, groupIds, status, limit, offset)
    }

    @Field
    suspend fun sharedPrayers(
        authentication: AuthenticationContext,
        community: ProfileCommunity,
        limit: Int = 50,
        offset: Int = 0
    ): Prayers {
        profilePermissionEvaluator.verifyAllowed(authentication, community.profile, PermissionAction.LIST)
        return prayerService.getSharedWithMe(community.profile.id, limit, offset)
    }
}