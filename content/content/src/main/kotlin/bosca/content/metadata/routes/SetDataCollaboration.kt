package bosca.content.metadata.routes

import bosca.content.metadata.model.DataCollaborationInput
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

@RouteController("/api/v1/content/metadata/{id}/data/collaboration", RouteMethod.PUT)
class SetDataCollaboration(
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val service: MetadataService,
    private val dataService: DataService,
) : Route<HttpStatusCode>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpStatusCode {
        val id = UUID.parse(call.pathParameters["id"] ?: throw IllegalArgumentException("id is required"))
        val version = call.request.queryParameters["version"]?.toIntOrNull() ?: 1
        val metadata = service.getById(id, version) ?: throw NoSuchElementException("No metadata found for id $id")
        val body = call.request.bodyBytes()
        metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.EDIT)
        dataService.setCollaboration(DataCollaborationInput(id, version, body))
        return HttpStatusCode.Accepted
    }
}
