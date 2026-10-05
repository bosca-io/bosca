package bosca.profile.organization.routes

import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationInput
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
import kotlinx.serialization.json.JsonNull

@RouteController("/api/v1/organizations/{id}", RouteMethod.POST)
class EditOrganization(
    private val organizationService: OrganizationService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator
) : Route<Organization>() {

    override fun serializer(): KSerializer<Organization> = Organization.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Organization {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val organization = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.EDIT)
        val organizationInput = call.receive<OrganizationInput>()
        organizationService.edit(organizationInput)
        return organization.copy(systemAttributes = JsonNull)
    }
}