package bosca.community.service

import bosca.community.model.*
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.PermissionInput
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing community groups and their membership.
 *
 * Community groups represent organizational units (families, small groups, or custom groups)
 * with configurable visibility levels (public, private, or hidden). Each community group
 * is backed by two security [Group] instances -- one for administrators and one for regular
 * users -- to control access through the permission system. Extends [PermissionService] to
 * enforce access control on [CommunityGroup] entities.
 *
 * Membership can be managed directly by administrators, or through self-service signup
 * tokens and email-based invitations.
 */
interface CommunityService : PermissionService<CommunityGroup, UUID> {

    /**
     * Grants a permission on a community group to a security group.
     *
     * @param permission the permission to add, containing the entity ID, group ID, and action
     * @return the created [EntityPermission] record
     */
    suspend fun addPermission(permission: PermissionInput): EntityPermission

    /**
     * Revokes a permission on a community group from a security group.
     *
     * @param permission the permission to remove, containing the entity ID, group ID, and action
     * @return the removed [EntityPermission] record
     */
    suspend fun deletePermission(permission: PermissionInput): EntityPermission

    /**
     * Retrieves all community groups that the specified profile is a member of.
     *
     * @param profileId the unique identifier of the user profile
     * @return a list of [CommunityGroup] instances the profile belongs to
     */
    suspend fun getGroups(profileId: UUID): List<CommunityGroup>

    /**
     * Retrieves a paginated list of all community groups, regardless of membership.
     *
     * @param limit the maximum number of groups to return
     * @param offset the number of groups to skip before returning results
     * @return a list of [CommunityGroup] instances for the requested page
     */
    suspend fun getGroups(limit: Int, offset: Long): List<CommunityGroup>

    /**
     * Returns the total number of community groups in the system.
     *
     * @return the count of all community groups
     */
    suspend fun getGroupCount(): Long

    /**
     * Retrieves a single community group by its unique identifier.
     *
     * @param id the unique identifier of the community group
     * @return the [CommunityGroup] if found, or `null` if no group exists with the given ID
     */
    suspend fun getGroup(id: UUID): CommunityGroup?

    /**
     * Retrieves the security group that holds administrator privileges for the specified
     * community group. Members of this group have elevated permissions over the community group.
     *
     * @param id the unique identifier of the community group
     * @return the admin [Group] if found, or `null` if the community group does not exist
     */
    suspend fun getAdminGroup(id: UUID): Group?

    /**
     * Retrieves the security group that holds standard user privileges for the specified
     * community group. Regular members are added to this group upon joining.
     *
     * @param id the unique identifier of the community group
     * @return the users [Group] if found, or `null` if the community group does not exist
     */
    suspend fun getUsersGroup(id: UUID): Group?

    /**
     * Creates a new community group along with its associated admin and users security groups.
     *
     * @param name the display name of the community group
     * @param description a textual description of the community group
     * @param type the group type classification (FAMILY, SMALL_GROUP, or CUSTOM)
     * @param visibility the visibility level controlling discoverability (PUBLIC, PRIVATE, or HIDDEN)
     * @param attributes optional JSON metadata to attach to the group
     * @return a [Triple] containing the created [CommunityGroup], the admin [Group], and the users [Group]
     */
    suspend fun createGroup(
        name: String,
        description: String,
        type: CommunityGroupType,
        visibility: CommunityVisibility,
        attributes: JsonElement? = null
    ): Triple<CommunityGroup, Group, Group>

    /**
     * Updates the editable fields of an existing community group. Each parameter is optional;
     * passing `null` for a field leaves the current value unchanged. When the group's name
     * changes, the descriptions of the backing admin and users security groups are refreshed
     * to remain consistent.
     *
     * @param id the unique identifier of the community group to update
     * @param name the new display name, or `null` to leave unchanged
     * @param description the new description, or `null` to leave unchanged
     * @param type the new group type classification, or `null` to leave unchanged
     * @param visibility the new visibility level, or `null` to leave unchanged
     * @param attributes the new JSON metadata, or `null` to leave unchanged
     * @return the updated [CommunityGroup]
     */
    suspend fun updateGroup(
        id: UUID,
        name: String? = null,
        description: String? = null,
        type: CommunityGroupType? = null,
        visibility: CommunityVisibility? = null,
        attributes: JsonElement? = null
    ): CommunityGroup

    /**
     * Adds a user profile as a member of the specified community group.
     *
     * @param communityGroupId the unique identifier of the community group
     * @param profileId the unique identifier of the user profile to add
     */
    suspend fun addMember(communityGroupId: UUID, profileId: UUID)

    /**
     * Removes a user profile from the membership of the specified community group.
     *
     * @param communityGroupId the unique identifier of the community group
     * @param profileId the unique identifier of the user profile to remove
     */
    suspend fun removeMember(communityGroupId: UUID, profileId: UUID)

    /**
     * Retrieves all members of the specified community group.
     *
     * @param communityGroupId the unique identifier of the community group
     * @return a list of [CommunityGroupMember] instances representing the group's members
     */
    suspend fun getMembers(communityGroupId: UUID): List<CommunityGroupMember>

    /**
     * Retrieves all active signup tokens for the specified community group.
     *
     * @param groupId the unique identifier of the community group
     * @return a list of [CommunityGroupSignupToken] instances associated with the group
     */
    suspend fun getSignupTokens(groupId: UUID): List<CommunityGroupSignupToken>

    /**
     * Looks up a signup token by its token string value.
     *
     * @param token the token string to look up
     * @return the [CommunityGroupSignupToken] if found and valid, or `null` if not found
     */
    suspend fun getSignupToken(token: String): CommunityGroupSignupToken?

    /**
     * Generates a new signup token for the specified community group. The token can be
     * shared with prospective members to allow self-service joining.
     *
     * @param groupId the unique identifier of the community group
     * @return the newly created [CommunityGroupSignupToken]
     */
    suspend fun addSignupToken(groupId: UUID): CommunityGroupSignupToken

    /**
     * Deletes a signup token, preventing further use for joining the community group.
     *
     * @param groupId the unique identifier of the community group
     * @param token the token string to delete
     */
    suspend fun deleteSignupToken(groupId: UUID, token: String)

    /**
     * Adds a user as a member of the community group associated with the given signup token.
     * The token is validated before the membership is created.
     *
     * @param token the signup token string authorizing the membership
     * @param principalId the unique identifier of the authenticated principal to add as a member
     */
    suspend fun addMemberByToken(token: String, principalId: UUID)

    /**
     * Adds a user as a member of a community group using an email-based invitation.
     * The email is matched to the appropriate community group invitation.
     *
     * @param email the email address associated with the invitation
     * @param principalId the unique identifier of the authenticated principal to add as a member
     */
    suspend fun addMemberByEmail(email: String, principalId: UUID)
}
