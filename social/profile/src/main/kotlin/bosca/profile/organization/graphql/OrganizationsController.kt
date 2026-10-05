package bosca.profile.organization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Organizations

@TypeController
class OrganizationsController(
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Organizations> {

    @Field
    suspend fun all(authentication: AuthenticationContext?, offset: Long, limit: Int): List<Organization> {
        if (!groupEvaluator.hasAdminGroup(authentication)) return emptyList()
        return organizationService.getAll(offset, limit)
    }

    @Field
    suspend fun organization(authentication: AuthenticationContext?, id: UUID): Organization {
        val organization = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authentication, organization, PermissionAction.VIEW)
        return organization
    }

    @Field
    suspend fun findByToken(token: String) = organizationService.getSignupToken(token)?.let {
        organizationService.getOrganization(it.organizationId)
    }

    @Field
    suspend fun findByEmail(authentication: AuthenticationContext, email: String) = organizationService.getSignupEmail(email).map {
        organizationService.getOrganization(it.organizationId)
    }
}