package bosca.community.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.community.model.CommunityActivity
import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupSignupToken
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.community.model.Prayer
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerStatus
import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.security.PrayerPermissionEvaluator
import bosca.community.service.CommunityActivityService
import bosca.community.service.CommunityChatService
import bosca.community.service.CommunityService
import bosca.community.service.PrayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

object CommunityMutation

private val OWNER_ALLOWED_STATUSES = setOf(
    PrayerStatus.ACTIVE,
    PrayerStatus.CANCELLED,
    PrayerStatus.ANSWERED
)

private val MODERATOR_ALLOWED_STATUSES = setOf(
    PrayerStatus.BLOCKED,
    PrayerStatus.ACTIVE,
    PrayerStatus.PENDING_APPROVAL
)

@TypeController
class CommunityMutationController(
    private val communityService: CommunityService,
    private val communityGroupPermissionEvaluator: CommunityGroupPermissionEvaluator,
    private val prayerPermissionEvaluator: PrayerPermissionEvaluator,
    private val communityChatService: CommunityChatService,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
    private val communityActivityService: CommunityActivityService,
    private val prayerService: PrayerService,
    private val securityService: SecurityService
) : GraphQLController<CommunityMutation> {

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, permission: PermissionInput): EntityPermission {
        val group = communityService.getGroup(permission.entityId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(
            authentication,
            group,
            PermissionAction.MANAGE
        )
        return communityService.addPermission(permission)
    }

    @Field
    suspend fun deletePermission(authentication: AuthenticationContext, permission: PermissionInput): EntityPermission {
        val group = communityService.getGroup(permission.entityId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(
            authentication,
            group,
            PermissionAction.MANAGE
        )
        return communityService.deletePermission(permission)
    }

    @Field
    suspend fun createGroup(
        authentication: AuthenticationContext,
        name: String,
        description: String,
        type: CommunityGroupType,
        visibility: CommunityVisibility,
        attributes: JsonElement?
    ): CommunityGroup {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getPrimaryProfile(principal.asPrincipal()) ?: error("No profile found for principal")
        val (communityGroup, adminGroup, _) = communityService.createGroup(name, description, type, visibility, attributes)
        communityService.addMember(communityGroup.id, profile.id)
        securityService.addPrincipalGroup(principal.id, adminGroup.id)
        communityChatService.createGroupChannel(
            communityGroup.id,
            "general",
            ChatChannelType.GROUP,
            null,
            profile.id,
        )
        return communityGroup
    }

    @Field
    suspend fun updateGroup(
        authentication: AuthenticationContext,
        id: UUID,
        name: String?,
        description: String?,
        type: CommunityGroupType?,
        visibility: CommunityVisibility?,
        attributes: JsonElement?
    ): CommunityGroup {
        val group = communityService.getGroup(id) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(
            authentication,
            group,
            PermissionAction.MANAGE
        )
        return communityService.updateGroup(id, name, description, type, visibility, attributes)
    }

    @Field
    suspend fun addMember(authentication: AuthenticationContext, groupId: UUID, profileId: UUID): Boolean {
        val group = communityService.getGroup(groupId) ?: return false
        communityGroupPermissionEvaluator.verifyAllowed(
            authentication,
            group,
            PermissionAction.MANAGE
        )
        communityService.addMember(groupId, profileId)
        return true
    }

    @Field
    suspend fun removeMember(authentication: AuthenticationContext, groupId: UUID, profileId: UUID): Boolean {
        val group = communityService.getGroup(groupId) ?: return false
        val principal = authentication.principal() ?: error("Not authenticated")
        val activeProfile = profileService.getPrimaryProfile(principal.asPrincipal())
        if (activeProfile?.id != profileId) {
            communityGroupPermissionEvaluator.verifyAllowed(
                authentication,
                group,
                PermissionAction.MANAGE
            )
        }
        communityService.removeMember(groupId, profileId)
        return true
    }

    @Field
    suspend fun createChannel(
        authentication: AuthenticationContext,
        groupId: UUID,
        name: String,
        type: ChatChannelType,
        attributes: JsonElement?
    ): ChatChannel {
        val group = communityService.getGroup(groupId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(
            authentication,
            group,
            PermissionAction.MANAGE
        )
        val principal = authentication.principal()?.asPrincipal() ?: error("Not authenticated")
        val profile = profileService.getPrimaryProfile(principal) ?: error("No profile found for principal")
        return communityChatService.createGroupChannel(groupId, name, type, attributes, profile.id)
    }

    @Field
    suspend fun createActivity(
        authentication: AuthenticationContext,
        groupId: UUID,
        name: String,
        description: String,
        type: String,
        content: JsonElement?,
        schedule: JsonElement?
    ): CommunityActivity {
        val group = communityService.getGroup(groupId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        return communityActivityService.createActivity(groupId, name, description, type, content, schedule)
    }

    @Field
    suspend fun updateActivity(
        authentication: AuthenticationContext,
        id: UUID,
        name: String?,
        description: String?,
        type: String?,
        content: JsonElement?,
        schedule: JsonElement?
    ): CommunityActivity {
        val activity = communityActivityService.getActivity(id) ?: error("Activity not found")
        val group = communityService.getGroup(activity.groupId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        return communityActivityService.updateActivity(id, name, description, type, content, schedule)
    }

    @Field
    suspend fun addPrayer(
        authentication: AuthenticationContext,
        groupId: UUID,
        title: String,
        content: JsonElement,
        attributes: JsonElement?
    ): Prayer {
        val group = communityService.getGroup(groupId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.VIEW)
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: error("No profile found for principal")
        return prayerService.addRequest(groupId, profile.id, title, content, attributes)
    }

    @Field
    suspend fun updatePrayerStatus(
        authentication: AuthenticationContext,
        id: UUID,
        status: PrayerStatus
    ): Prayer {
        val prayer = prayerService.getRequest(id) ?: error("Prayer not found")
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        val isOwner = prayer.profileId == profile.id
        if (isOwner) {
            require(status in OWNER_ALLOWED_STATUSES) {
                "Owner cannot transition to $status"
            }
        } else {
            prayerPermissionEvaluator.verifyAllowed(authentication, prayer, PermissionAction.MANAGE)
            require(status in MODERATOR_ALLOWED_STATUSES) {
                "Moderator cannot transition to $status"
            }
        }
        return prayerService.updateStatus(id, status)
    }

    @Field
    suspend fun markPrayed(authentication: AuthenticationContext, id: UUID): Prayer {
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        prayerService.markPrayed(id, profile.id)
        return prayerService.getRequest(id) ?: error("Prayer not found")
    }

    @Field
    suspend fun unmarkPrayed(authentication: AuthenticationContext, id: UUID): Prayer {
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        prayerService.unmarkPrayed(id, profile.id)
        return prayerService.getRequest(id) ?: error("Prayer not found")
    }

    @Field
    suspend fun likePrayer(authentication: AuthenticationContext, id: UUID): Prayer {
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        prayerService.likePrayer(id, profile.id)
        return prayerService.getRequest(id) ?: error("Prayer not found")
    }

    @Field
    suspend fun unlikePrayer(authentication: AuthenticationContext, id: UUID): Prayer {
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        prayerService.unlikePrayer(id, profile.id)
        return prayerService.getRequest(id) ?: error("Prayer not found")
    }

    @Field
    suspend fun addPrayerComment(
        authentication: AuthenticationContext,
        prayerId: UUID,
        content: String,
        parentId: Long? = null,
        attributes: JsonElement? = null
    ): PrayerComment {
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        return prayerService.addComment(prayerId, profile.id, content, parentId, attributes)
    }

    @Field
    suspend fun deletePrayerComment(
        authentication: AuthenticationContext,
        prayerId: UUID,
        commentId: Long
    ): Boolean {
        authentication.principal() ?: error("Not authenticated")
        prayerService.deleteComment(prayerId, commentId)
        return true
    }

    @Field
    suspend fun sendPrayerToChat(
        authentication: AuthenticationContext,
        prayerId: UUID,
        channelId: UUID,
        clientId: UUID
    ): Boolean {
        prayerService.getRequest(prayerId) ?: error("Prayer not found")
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        val content = listOf(MessageContent(MessageContentType.PRAYER, prayerId.toString()))
        communityChatService.sendGroupMessage(channelId, profile.id, clientId, content)
        return true
    }

    @Field
    suspend fun sharePrayer(authentication: AuthenticationContext, id: UUID, profileId: UUID): Prayer {
        val prayer = prayerService.getRequest(id) ?: error("Prayer not found")
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        require(prayer.profileId == profile.id) { "Only the prayer owner can share" }
        prayerService.sharePrayer(id, profileId)
        return prayer
    }

    @Field
    suspend fun unsharePrayer(authentication: AuthenticationContext, id: UUID, profileId: UUID): Prayer {
        val prayer = prayerService.getRequest(id) ?: error("Prayer not found")
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        require(prayer.profileId == profile.id) { "Only the prayer owner can unshare" }
        prayerService.unsharePrayer(id, profileId)
        return prayer
    }

    @Field
    suspend fun suppressPrayerAnniversaries(
        authentication: AuthenticationContext,
        id: UUID,
        suppress: Boolean
    ): Prayer {
        val prayer = prayerService.getRequest(id) ?: error("Prayer not found")
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull()
            ?: error("No profile found for principal")
        require(prayer.profileId == profile.id) { "Only the prayer owner can suppress anniversaries" }
        return prayerService.suppressAnniversaries(id, suppress)
    }

    @Field
    suspend fun deletePrayer(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        val prayer = prayerService.getRequest(id) ?: return false
        val principal = authentication.principal() ?: error("Not authenticated")
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: error("No profile found for principal")
        if (prayer.profileId != profile.id) {
            groupEvaluator.verifyHasAdminGroup(authentication)
        }
        prayerService.deleteRequest(id)
        return true
    }

    @Field
    suspend fun addCommunityGroupSignupToken(authentication: AuthenticationContext, groupId: UUID): CommunityGroupSignupToken {
        val group = communityService.getGroup(groupId) ?: error("Group not found")
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        return communityService.addSignupToken(groupId)
    }

    @Field
    suspend fun join(authentication: AuthenticationContext, token: String): CommunityGroup {
        val principal = authentication.principal() ?: error("Not authenticated")
        val signupToken = communityService.getSignupToken(token) ?: error("Invalid token")
        communityService.addMemberByToken(token, principal.id)
        return communityService.getGroup(signupToken.groupId) ?: error("Group not found")
    }

    @Field
    suspend fun deleteCommunityGroupSignupToken(authentication: AuthenticationContext, groupId: UUID, token: String): Boolean {
        val group = communityService.getGroup(groupId) ?: return false
        communityGroupPermissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        communityService.deleteSignupToken(groupId, token)
        return true
    }
}
