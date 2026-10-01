package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/content/metadata/bible/{id}", method = RouteMethod.DELETE)
class DeleteBible(
    private val groups: GroupEvaluator,
    private val metadataService: MetadataService,
) : Route<Unit>() {

    override fun serializer(): KSerializer<Unit>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        groups.verifyHasAdminGroup(authenticationContext)
        val metadata = metadataService.getById(UUID.parse(call.pathParameters["id"] ?: error("missing id"))) ?: error("Metadata not found")
        metadataService.delete(metadata)
    }
}