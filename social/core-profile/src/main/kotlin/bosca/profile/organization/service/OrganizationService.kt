package bosca.profile.organization.service

import bosca.graphql.Batch
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationDomainInput
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.model.OrganizationMember
import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupEmailInput
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.model.OrganizationSignupTokenInput
import bosca.profile.profile.model.ProfileInput
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID

/**
 * Service for managing organizations, their memberships, permissions, domains, and
 * signup mechanisms.
 *
 * Organizations are permissible entities that group profiles together. They support
 * multiple signup flows (token-based, email-based, and domain-based auto-join) and
 * a permission model that grants groups specific actions on the organization.
 *
 * Extends [PermissionService] to inherit entity-level permission querying and batch
 * permission loading capabilities.
 */
interface OrganizationService : PermissionService<Organization, UUID> {

    /**
     * Retrieves a paginated list of all organizations.
     *
     * @param offset the number of organizations to skip for pagination
     * @param limit the maximum number of organizations to return
     * @return the list of [Organization] instances
     */
    suspend fun getAll(offset: Long, limit: Int): List<Organization>

    /**
     * Retrieves a single organization by its identifier.
     *
     * @param id the UUID of the organization
     * @return the [Organization] matching the given [id]
     */
    suspend fun getOrganization(id: UUID): Organization

    /**
     * Retrieves the organization associated with a given profile, if one exists.
     *
     * @param profileId the UUID of the profile to look up
     * @return the [Organization] linked to the profile, or `null` if the profile has no organization
     */
    suspend fun getOrganizationByProfile(profileId: UUID): Organization?

    /**
     * Retrieves multiple organizations by their identifiers in a single operation.
     *
     * @param ids the list of organization UUIDs to fetch
     * @return the list of matching [Organization] instances
     */
    suspend fun getOrganizations(ids: List<UUID>): List<Organization>

    /**
     * Populates a [Batch] with organizations keyed by UUID. Used for efficient bulk loading,
     * typically for GraphQL DataLoader integration.
     *
     * @param batch the batch to populate, keyed by organization UUID
     */
    suspend fun addOrganizationsToBatch(batch: Batch<UUID, Organization>)

    /**
     * Looks up a signup token by its string value.
     *
     * @param token the token string to search for
     * @return the matching [OrganizationSignupToken], or `null` if the token is invalid or expired
     */
    suspend fun getSignupToken(token: String): OrganizationSignupToken?

    /**
     * Retrieves all signup tokens associated with an organization.
     *
     * @param organizationId the UUID of the organization
     * @return the list of [OrganizationSignupToken] instances for the organization
     */
    suspend fun getSignupTokens(organizationId: UUID): List<OrganizationSignupToken>

    /**
     * Creates a new signup token for the specified organization. The token is generated
     * with a secure random value and an expiration period.
     *
     * @param organizationId the UUID of the organization to create the token for
     * @param token the input specifying the signup group type (administrators, users, etc.)
     * @return the newly created [OrganizationSignupToken]
     */
    suspend fun addSignupToken(organizationId: UUID, token: OrganizationSignupTokenInput): OrganizationSignupToken

    /**
     * Deletes a signup token from an organization.
     *
     * @param organizationId the UUID of the organization that owns the token
     * @param token the token string to delete
     */
    suspend fun deleteSignupToken(organizationId: UUID, token: String)

    /**
     * Retrieves all signup email records that match a given email address, potentially
     * across multiple organizations.
     *
     * @param email the email address to search for
     * @return the list of [OrganizationSignupEmail] records matching the email
     */
    suspend fun getSignupEmail(email: String): List<OrganizationSignupEmail>

    /**
     * Retrieves all signup email records associated with an organization.
     *
     * @param organizationId the UUID of the organization
     * @return the list of [OrganizationSignupEmail] instances for the organization
     */
    suspend fun getSignupEmails(organizationId: UUID): List<OrganizationSignupEmail>

    /**
     * Creates a new signup email record for the specified organization, allowing users
     * with the given email to join.
     *
     * @param organizationId the UUID of the organization
     * @param email the input specifying the email address and signup group type
     * @return the newly created [OrganizationSignupEmail]
     */
    suspend fun addSignupEmail(organizationId: UUID, email: OrganizationSignupEmailInput): OrganizationSignupEmail

    /**
     * Deletes a signup email record from an organization.
     *
     * @param organizationId the UUID of the organization
     * @param email the email address to remove from the signup list
     */
    suspend fun deleteSignupEmail(organizationId: UUID, email: String)

    /**
     * Creates a new organization along with its associated profile. Optionally assigns
     * ownership to a specific principal.
     *
     * @param organization the organization specification including name, attributes, visibility, and optional domains/tokens
     * @param profile the profile input to create alongside the organization
     * @param principalId the UUID of the principal who will own the organization, or `null` for no explicit owner
     * @return the newly created [Organization]
     */
    suspend fun add(organization: OrganizationInput, profile: ProfileInput, principalId: UUID? = null): Organization

    /**
     * Updates an existing organization's properties (name, attributes, visibility, etc.).
     *
     * @param organization the updated organization specification; the [OrganizationInput.id] identifies which organization to modify
     * @return the modified [Organization]
     */
    suspend fun edit(organization: OrganizationInput): Organization

    /**
     * Updates an existing organization's properties and its associated profile simultaneously.
     *
     * @param organization the updated organization specification
     * @param profile the updated profile input
     * @return the modified [Organization]
     */
    suspend fun edit(organization: OrganizationInput, profile: ProfileInput): Organization

    /**
     * Deletes an organization by its identifier.
     *
     * @param id the UUID of the organization to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Grants a permission to a group on an organization.
     *
     * @param permission the [OrganizationPermission] specifying the organization, group, and action to grant
     */
    suspend fun addPermission(permission: OrganizationPermission)

    /**
     * Revokes a specific permission action from a group on an organization.
     *
     * @param id the UUID of the organization
     * @param groupId the UUID of the group to revoke the permission from
     * @param action the [PermissionAction] to revoke
     */
    suspend fun removePermission(id: UUID, groupId: UUID, action: PermissionAction)

    /**
     * Retrieves all permissions granted on a specific organization.
     *
     * @param id the UUID of the organization
     * @return the list of [EntityPermission] entries for the organization
     */
    suspend fun getPermissions(id: UUID): List<EntityPermission>

    /**
     * Associates a domain with an organization. Domains can optionally enable auto-join,
     * where users with matching email domains are automatically added as members.
     *
     * @param id the UUID of the organization
     * @param domain the domain configuration to add
     */
    suspend fun addDomain(id: UUID, domain: OrganizationDomainInput)

    /**
     * Removes a domain association from an organization.
     *
     * @param id the UUID of the organization
     * @param domain the domain string to remove (e.g., "example.com")
     */
    suspend fun removeDomain(id: UUID, domain: String)

    /**
     * Retrieves all domains associated with an organization.
     *
     * @param id the UUID of the organization
     * @return the list of [OrganizationDomain] entries for the organization
     */
    suspend fun getDomains(id: UUID): List<OrganizationDomain>

    /**
     * Adds a principal as a member of the organization associated with the given domain.
     * Typically used during auto-join flows where a user's email domain matches an
     * organization's registered domain.
     *
     * @param domain the domain string used to identify the target organization
     * @param principalId the UUID of the principal to add as a member
     */
    suspend fun addMemberByDomain(domain: String, principalId: UUID)

    /**
     * Adds a principal as a member of the organization associated with the given signup token.
     *
     * @param token the signup token string used to identify the target organization and group
     * @param principalId the UUID of the principal to add as a member
     */
    suspend fun addMemberByToken(token: String, principalId: UUID)

    /**
     * Adds a principal as a member of the organization associated with the given signup email.
     *
     * @param email the signup email address used to identify the target organization and group
     * @param principalId the UUID of the principal to add as a member
     */
    suspend fun addMemberByEmail(email: String, principalId: UUID)

    /**
     * Directly adds a principal as a member of a specific organization.
     *
     * @param id the UUID of the organization
     * @param principalId the UUID of the principal to add as a member
     */
    suspend fun addMember(id: UUID, principalId: UUID)

    /**
     * Removes a principal from an organization's membership.
     *
     * @param id the UUID of the organization
     * @param principalId the UUID of the principal to remove
     */
    suspend fun removeMember(id: UUID, principalId: UUID)

    /**
     * Retrieves a paginated list of members belonging to an organization.
     *
     * @param id the UUID of the organization
     * @param offset the number of members to skip for pagination
     * @param limit the maximum number of members to return
     * @return the list of [OrganizationMember] entries
     */
    suspend fun getMembers(id: UUID, offset: Long, limit: Int): List<OrganizationMember>

    /**
     * Returns the total number of members in an organization.
     *
     * @param id the UUID of the organization
     * @return the member count
     */
    suspend fun getMemberCount(id: UUID): Long

    /**
     * Retrieves all organization memberships for a specific principal.
     *
     * @param principalId the UUID of the principal
     * @return the list of [OrganizationMember] entries representing the principal's memberships
     */
    suspend fun getMemberOrganizations(principalId: UUID): List<OrganizationMember>

    /**
     * Retrieves all organization memberships for multiple principals in a single operation.
     *
     * @param principalIds the list of principal UUIDs to look up
     * @return the combined list of [OrganizationMember] entries for all specified principals
     */
    suspend fun getMemberOrganizations(principalIds: List<UUID>): List<OrganizationMember>
}