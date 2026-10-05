package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/** Checks document editing access without transferring the collaboration state. */
@RouteController("/api/v1/content/metadata/{id}/document/collaboration", RouteMethod.HEAD, RouteAuthentication.REQUIRED)
class DocumentCollaborationAccess(
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val service: MetadataService,
) : Route<HttpStatusCode>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpStatusCode {
        val id = UUID.parse(call.pathParameters["id"] ?: throw IllegalArgumentException("id is required"))
        val version = call.request.queryParameters["version"]?.toIntOrNull() ?: 1
        val metadata = service.getById(id, version) ?: return HttpStatusCode.NotFound
        metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.EDIT)
        return HttpStatusCode.OK
    }
}
