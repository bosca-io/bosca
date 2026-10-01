package bosca.content.metadata.routes

import bosca.content.metadata.model.DocumentCollaborationInput
import bosca.content.metadata.service.DocumentService
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
import yks.utils.Doc
import yks.utils.applyUpdate
import yks.utils.encodeStateAsUpdate

@RouteController("/api/v1/content/metadata/{id}/document/collaboration", RouteMethod.PUT)
class SetDocumentCollaboration(
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val service: MetadataService,
    private val documentService: DocumentService,
) : Route<HttpStatusCode>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpStatusCode {
        val id = UUID.parse(call.pathParameters["id"] ?: throw IllegalArgumentException("id is required"))
        val version = call.request.queryParameters["version"]?.toIntOrNull() ?: 1
        val metadata = service.getById(id, version) ?: throw NoSuchElementException("No metadata found for id $id")
        val body = call.request.bodyBytes()
        metadataPermissionEvaluator.verifyContentAllowed(authenticationContext, metadata, PermissionAction.EDIT)
        val existing = documentService.getCollaboration(id, version)
        val merged = if (existing != null && existing.content.isNotEmpty()) {
            val doc = Doc()
            applyUpdate(doc, existing.content)
            applyUpdate(doc, body)
            encodeStateAsUpdate(doc)
        } else {
            body
        }
        documentService.setCollaboration(DocumentCollaborationInput(id, version, merged))
        return HttpStatusCode.Accepted
    }
}