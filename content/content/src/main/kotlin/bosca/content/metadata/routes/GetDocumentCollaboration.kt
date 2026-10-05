package bosca.content.metadata.routes

import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/content/metadata/{id}/document/collaboration")
class GetDocumentCollaboration(
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val service: MetadataService,
    private val documentService: DocumentService,
) : Route<ByteArray>() {

    override fun serializer(): KSerializer<ByteArray>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): ByteArray? {
        val id = UUID.parse(call.pathParameters["id"] ?: throw IllegalArgumentException("id is required"))
        val version = call.request.queryParameters["version"]?.toIntOrNull() ?: 1
        val metadata = service.getById(id, version) ?: return null
        metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.EDIT)
        return documentService.getCollaboration(metadata.id, version)?.content
    }
}