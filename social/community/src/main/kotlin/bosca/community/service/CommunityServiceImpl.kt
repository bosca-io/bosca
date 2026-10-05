package bosca.community.service

import bosca.chat.model.ChatChannelRoles
import bosca.chat.service.ChatService
import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupMember
import bosca.community.model.CommunityGroupPermission
import bosca.community.model.CommunityGroupSignupToken
import bosca.community.model.CommunityGroupSignupTokenInput
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.community.repository.CommunityGroupPermissionRepository
import bosca.community.repository.CommunityGroupRepository
import bosca.community.repository.CommunityGroupSignupTokenRepository
import bosca.community.repository.CommunityGroupSignupEmailRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.profile.profile.service.ProfileService
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class CommunityServiceImpl(
    private val communityGroupRepository: CommunityGroupRepository,
    private val communityGroupPermissionRepository: CommunityGroupPermissionRepository,
    private val communityGroupSignupTokenRepository: CommunityGroupSignupTokenRepository,
    private val communitySignupEmailRepository: CommunityGroupSignupEmailRepository,
    private val securityService: SecurityService,
    private val profileService: ProfileService,
    private val chatService: ChatService,
) : CommunityService {

    override suspend fun addPermission(permission: PermissionInput): EntityPermission {
        communityGroupPermissionRepository.addPermission(permission.entityId, permission.groupId, permission.action)
        return CommunityGroupPermission(permission.entityId, permission.groupId, permission.action)
    }

    override suspend fun deletePermission(permission: PermissionInput): EntityPermission {
        communityGroupPermissionRepository.deletePermission(permission.entityId, permission.groupId, permission.action)
        return CommunityGroupPermission(permission.entityId, permission.groupId, permission.action)
    }

    override suspend fun getPermissions(entity: CommunityGroup): List<EntityPermission> {
        return communityGroupPermissionRepository.getPermissionsByCommunityGroupId(entity.id)
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = communityGroupPermissionRepository.getPermissionsByCommunityGroupIds(batch.keys)
        batch.setData(batch.keys, permissions.groupBy { it.entityId })
    }

    override suspend fun getGroups(profileId: UUID): List<CommunityGroup> {
        return communityGroupRepository.getGroupsByProfileId(profileId)
    }

    override suspend fun getGroups(limit: Int, offset: Long): List<CommunityGroup> {
        return communityGroupRepository.getGroups(limit, offset)
    }

    override suspend fun getGroupCount(): Long {
        return communityGroupRepository.getGroupCount()
    }

    override suspend fun getGroup(id: UUID): CommunityGroup? {
        return communityGroupRepository.getGroup(id)
    }

    override suspend fun getAdminGroup(id: UUID): Group? {
        return securityService.getGroupByName(CommunitySecurityGroups.administratorsName(id), GroupType.SYSTEM)
    }

    override suspend fun getUsersGroup(id: UUID): Group? {
        return securityService.getGroupByName(CommunitySecurityGroups.usersName(id), GroupType.SYSTEM)
    }

    override suspend fun createGroup(
        name: String,
        description: String,
        type: CommunityGroupType,
        visibility: CommunityVisibility,
        attributes: JsonElement?
    ): Triple<CommunityGroup, Group, Group> = transaction {
        val communityGroup = communityGroupRepository.createGroup(name, description, type, visibility, attributes)
        val adminGroup = securityService.addGroup(
            Group(
                name = CommunitySecurityGroups.administratorsName(communityGroup.id),
                description = "Community Group: $name Administrators",
                type = GroupType.SYSTEM,
            )
        )
        val userGroup = securityService.addGroup(
            Group(
                name = CommunitySecurityGroups.usersName(communityGroup.id),
                description = "Community Group: $name Users",
                type = GroupType.SYSTEM,
            )
        )
        addPermission(
            PermissionInput(
                entityId = communityGroup.id,
                groupId = adminGroup.id,
                action = PermissionAction.MANAGE
            )
        )
        addPermission(
            PermissionInput(
                entityId = communityGroup.id,
                groupId = userGroup.id,
                action = PermissionAction.VIEW
            )
        )
        Triple(communityGroup, adminGroup, userGroup)
    }

    override suspend fun updateGroup(
        id: UUID,
        name: String?,
        description: String?,
        type: CommunityGroupType?,
        visibility: CommunityVisibility?,
        attributes: JsonElement?
    ): CommunityGroup = transaction {
        val updated = communityGroupRepository.updateGroup(id, name, description, type, visibility, attributes)
        if (name != null) {
            getAdminGroup(id)?.let { adminGroup ->
                securityService.editGroup(adminGroup.copy(description = "Community Group: ${updated.name} Administrators"))
            }
            getUsersGroup(id)?.let { userGroup ->
                securityService.editGroup(userGroup.copy(description = "Community Group: ${updated.name} Users"))
            }
        }
        updated
    }

    override suspend fun addMember(communityGroupId: UUID, profileId: UUID) = transaction {
        val group = communityGroupRepository.getGroup(communityGroupId) ?: throw IllegalArgumentException("Group not found")
        val members = communityGroupRepository.getMembers(communityGroupId)
        val existingMember = members.find { it.profileId == profileId }
        val limit = when (group.type) {
            CommunityGroupType.FAMILY -> 10
            CommunityGroupType.SMALL_GROUP -> 50
            CommunityGroupType.CUSTOM -> 500
        }
        if (existingMember == null) {
            if (members.size >= limit) {
                throw IllegalArgumentException("Group member limit reached for ${group.type} ($limit)")
            }
        }
        communityGroupRepository.addMember(communityGroupId, profileId)
        val profile = profileService.getById(profileId)
        val userGroup = getUsersGroup(communityGroupId) ?: error("User group not found")
        val principalId = profile.principal ?: error("missing principal id")
        securityService.addPrincipalGroup(principalId, userGroup.id)
        if (chatService.canParticipate(profileId)) {
            chatService.getChannelsByGroupId(communityGroupId).forEach { channel ->
                chatService.joinChannel(
                    channelId = channel.id,
                    profileId = profileId,
                    role = ChatChannelRoles.MEMBER,
                    notifyExistingMembers = false,
                )
            }
        }
    }

    override suspend fun removeMember(communityGroupId: UUID, profileId: UUID) = transaction<Unit> {
        val profile = profileService.getById(profileId)
        val principalId = profile.principal ?: error("missing principal id")
        chatService.getChannelsByGroupId(communityGroupId).forEach { channel ->
            if (chatService.getMember(channel.id, profileId) != null) {
                chatService.leaveChannel(channel.id, profileId)
            }
        }
        communityGroupRepository.removeMember(communityGroupId, profileId)
        getUsersGroup(communityGroupId)?.let { securityService.removePrincipalGroup(principalId, it.id) }
        getAdminGroup(communityGroupId)?.let { securityService.removePrincipalGroup(principalId, it.id) }
    }

    override suspend fun getMembers(communityGroupId: UUID): List<CommunityGroupMember> {
        return communityGroupRepository.getMembers(communityGroupId)
    }

    override suspend fun getSignupTokens(groupId: UUID): List<CommunityGroupSignupToken> {
        return communityGroupSignupTokenRepository.getAll(groupId)
    }

    override suspend fun getSignupToken(token: String): CommunityGroupSignupToken? {
        return communityGroupSignupTokenRepository.getToken(token)
    }

    override suspend fun addSignupToken(groupId: UUID): CommunityGroupSignupToken {
        return communityGroupSignupTokenRepository.add(CommunityGroupSignupTokenInput().toCommunityGroupSignupToken(groupId))
    }

    override suspend fun deleteSignupToken(groupId: UUID, token: String) {
        communityGroupSignupTokenRepository.delete(groupId, token)
    }

    override suspend fun addMemberByToken(token: String, principalId: UUID) {
        val signupToken = communityGroupSignupTokenRepository.getToken(token) ?: return
        if (signupToken.expires.isBefore(java.time.OffsetDateTime.now())) {
            return
        }
        val profile = profileService.getByPrincipal(principalId).firstOrNull() ?: return
        addMember(signupToken.groupId, profile.id)
    }

    override suspend fun addMemberByEmail(email: String, principalId: UUID) {
        val profile = profileService.getByPrincipal(principalId).firstOrNull() ?: return
        communitySignupEmailRepository.getEmail(email).forEach {
            addMember(it.groupId, profile.id)
        }
    }
}
