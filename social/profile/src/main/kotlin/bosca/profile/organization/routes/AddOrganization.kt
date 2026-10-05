package bosca.profile.organization.routes

import bosca.profile.organization.model.AddOrganizationRequest
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/organizations", method = RouteMethod.POST)
class AddOrganization(
    private val organizationService: OrganizationService,
) : Route<Organization>() {

    override fun serializer(): KSerializer<Organization> = Organization.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Organization? {
        val principalId = authenticationContext.principal()?.id ?: throw SecurityException()
        val request = call.receive<AddOrganizationRequest>()
        return organizationService.add(
            request.organization,
            request.profile,
            principalId
        )
    }
}
