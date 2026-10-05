package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import kotlinx.serialization.json.JsonElement

@TypeController
class OrganizationController(
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator,
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Organization> {

    @Field
    fun id(organization: Organization) = organization.id

    @Field
    fun name(organization: Organization) = organization.name

    @Field
    fun attributes(organization: Organization) = organization.attributes

    @Field
    suspend fun systemAttributes(authenticationContext: AuthenticationContext, organization: Organization): JsonElement? {
        if (!organizationPermissionEvaluator.isAllowed(authenticationContext, organization, PermissionAction.MANAGE)) return null
        return organization.systemAttributes
    }

    @Field
    suspend fun profile(organization: Organization) = profileService.getById(organization.profileId)

    @Field
    suspend fun signupTokens(authenticationContext: AuthenticationContext, organization: Organization): List<OrganizationSignupToken> {
        if (!organizationPermissionEvaluator.isAllowed(authenticationContext, organization, PermissionAction.MANAGE)) return emptyList()
        return organizationService.getSignupTokens(organization.id)
    }

    @Field
    suspend fun signupEmails(authenticationContext: AuthenticationContext, organization: Organization): List<OrganizationSignupEmail> {
        if (!organizationPermissionEvaluator.isAllowed(authenticationContext, organization, PermissionAction.MANAGE)) return emptyList()
        return organizationService.getSignupEmails(organization.id)
    }

    @Field
    suspend fun domains(authenticationContext: AuthenticationContext, organization: Organization): List<OrganizationDomain> {
        if (!organizationPermissionEvaluator.isAllowed(authenticationContext, organization, PermissionAction.MANAGE)) return emptyList()
        return organizationService.getDomains(organization.id)
    }

    @Field
    suspend fun permissions(authenticationContext: AuthenticationContext, organization: Organization): List<OrganizationPermission> {
        if (!organizationPermissionEvaluator.isAllowed(authenticationContext, organization, PermissionAction.MANAGE)) return emptyList()
        return organizationService.getPermissions(organization.id).map {
            it as OrganizationPermission
        }
    }

    @Field
    fun visibility(organization: Organization) = organization.visibility

    @Field
    fun created(organization: Organization) = organization.created

    @Field
    fun modified(organization: Organization) = organization.modified

    @Field
    suspend fun memberCount(authenticationContext: AuthenticationContext, organization: Organization): Long {
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.MANAGE)
        return organizationService.getMemberCount(organization.id)
    }

    @Field
    suspend fun managers(authenticationContext: AuthenticationContext, organization: Organization): List<Principal> {
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.MANAGE)
        val permissions = organizationService.getPermissions(organization)
        val managerPermission = permissions.first { it.action == PermissionAction.MANAGE }
        val managerGroup = securityService.getGroupById(managerPermission.groupId)
        val principals = securityService.getPrincipalsByGroup(managerGroup)
        return principals
    }

    @Field
    suspend fun members(authenticationContext: AuthenticationContext, organization: Organization, offset: Long, limit: Int): List<Principal> {
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.MANAGE)
        val members = organizationService.getMembers(organization.id, offset, limit)
        val principals = securityService.getPrincipalsById(members.map { it.principalId })
        return principals
    }
}