package bosca.profile.organization.routes

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

@RouteController("/api/v1/organizations/{id}/token/{type}", RouteMethod.DELETE)
class DeleteOrganizationToken(
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator
) : Route<Unit>() {

    override fun serializer(): KSerializer<Unit>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val type: String by call.pathParameters
        val organization = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.MANAGE)
        organizationService.deleteSignupToken(organization.id, type)
    }
}