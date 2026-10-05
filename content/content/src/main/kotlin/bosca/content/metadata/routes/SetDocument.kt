package bosca.content.metadata.routes

import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.Document
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SetDocumentRequest(
    val document: DocumentInput
)

@RouteController("/api/v1/content/metadata/{id}/{version}/document", method = RouteMethod.POST)
class SetDocument(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val documentService: DocumentService,
    private val json: Json
) : Route<Unit>() {

    override fun serializer(): KSerializer<Unit>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val version = call.pathParameters["version"]?.toIntOrNull() ?: error("missing version")
        val metadata = metadataService.getById(id, version) ?: error("Metadata not found")
        metadataPermissionEvaluator.verifyAllowed(authenticationContext, metadata, PermissionAction.EDIT)
        val request = if (call.request.contentType().toString() == "application/x-www-form-urlencoded") {
            val parameters = call.receiveParameters()
            val document = json.decodeFromString(DocumentInput.serializer(), parameters["document"] ?: error("missing document"))
            SetDocumentRequest(document)
        } else {
            call.receive<SetDocumentRequest>()
        }
        val sync = parseCollaborationSync(call.request.queryParameters["collaborationSync"])
        documentService.setDocument(metadata, request.document, sync)
    }

    /**
     * Parses the optional `collaborationSync` query parameter. Returns [CollaborationSyncMode.NONE]
     * when the parameter is absent. An *unrecognized* value is rejected — silently downgrading
     * a misspelled `RSET` (or similar) to `NONE` would defeat the entire point of opting in,
     * since the caller would never see the failure. The Route base class translates the thrown
     * [IllegalStateException] into HTTP 400.
     */
    private fun parseCollaborationSync(raw: String?): CollaborationSyncMode {
        if (raw.isNullOrBlank()) return CollaborationSyncMode.NONE
        return try {
            CollaborationSyncMode.valueOf(raw.uppercase())
        } catch (_: IllegalArgumentException) {
            error("invalid collaborationSync value `$raw`; expected one of ${CollaborationSyncMode.entries.joinToString { it.name }}")
        }
    }
}