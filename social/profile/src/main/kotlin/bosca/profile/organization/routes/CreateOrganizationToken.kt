package bosca.profile.organization.routes

import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.model.OrganizationSignupTokenInput
import bosca.profile.organization.service.OrganizationService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/organizations/{id}/token/{type}", RouteMethod.POST)
class CreateOrganizationToken(
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator
) : Route<OrganizationSignupToken>() {

    override fun serializer(): KSerializer<OrganizationSignupToken> = OrganizationSignupToken.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): OrganizationSignupToken {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val type: String by call.pathParameters
        val organization = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.MANAGE)
        val typeEnum = when (type) {
            "admins" -> OrganizationSignupGroupType.ADMINISTRATORS
            "users" -> OrganizationSignupGroupType.USERS
            else -> OrganizationSignupGroupType.valueOf(type.uppercase())
        }
        val newToken = organizationService.addSignupToken(organization.id, OrganizationSignupTokenInput(typeEnum))
        return newToken
    }
}