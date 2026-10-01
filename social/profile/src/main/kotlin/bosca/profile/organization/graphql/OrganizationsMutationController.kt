package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationDomainInput
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.model.OrganizationSignupEmailInput
import bosca.profile.organization.model.OrganizationSignupTokenInput
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.model.ProfileInput
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object OrganizationsMutation

@TypeController
class OrganizationsMutationController(
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<OrganizationsMutation> {

    // NOTE: intentionally open, this is for org signup and things like that
    @Field
    suspend fun add(organization: OrganizationInput, profile: ProfileInput): Organization {
        return organizationService.add(organization, profile)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, organization: OrganizationInput, profile: ProfileInput): Organization {
        val current = organizationService.getOrganization(organization.id)
        organizationPermissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
        return organizationService.edit(organization, profile)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val current = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authentication, current, PermissionAction.DELETE)
        organizationService.delete(id)
        return true
    }

    @Field
    suspend fun addOrganizationDomain(authentication: AuthenticationContext, id: UUID, domain: OrganizationDomainInput): Organization {
        val current = organizationService.getOrganization(id)
        groupEvaluator.verifyHasAdminGroup(authentication)
        organizationService.addDomain(id, domain)
        return current
    }

    @Field
    suspend fun removeOrganizationDomain(authentication: AuthenticationContext, id: UUID, domain: String): Organization {
        val current = organizationService.getOrganization(id)
        groupEvaluator.verifyHasAdminGroup(authentication)
        organizationService.removeDomain(id, domain)
        return current
    }

    @Field
    suspend fun addSignupToken(authentication: AuthenticationContext, id: UUID, token: OrganizationSignupTokenInput): Organization {
        val current = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authentication, current, PermissionAction.MANAGE)
        organizationService.addSignupToken(id, token)
        return current
    }

    @Field
    suspend fun deleteSignupToken(authentication: AuthenticationContext, id: UUID, token: String): Organization {
        val current = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authentication, current, PermissionAction.MANAGE)
        organizationService.deleteSignupToken(id, token)
        return current
    }

    @Field
    suspend fun addSignupEmailToken(authentication: AuthenticationContext, id: UUID, token: OrganizationSignupEmailInput): Organization {
        val current = organizationService.getOrganization(id)
        groupEvaluator.verifyHasAdminGroup(authentication)
        organizationService.addSignupEmail(id, token)
        return current
    }

    @Field
    suspend fun deleteSignupEmailToken(authentication: AuthenticationContext, id: UUID, token: String): Organization {
        val current = organizationService.getOrganization(id)
        groupEvaluator.verifyHasAdminGroup(authentication)
        organizationService.deleteSignupEmail(id, token)
        return current
    }

    @Field
    suspend fun addMember(authentication: AuthenticationContext, id: UUID, principalId: UUID): Boolean {
        val current = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authentication, current, PermissionAction.MANAGE)
        organizationService.addMember(id, principalId)
        return true
    }

    @Field
    suspend fun removeMember(authentication: AuthenticationContext, id: UUID, principalId: UUID): Boolean {
        val current = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authentication, current, PermissionAction.MANAGE)
        organizationService.removeMember(id, principalId)
        return true
    }
}