package bosca.content.collection.routes

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlin.uuid.toKotlinUuid

@RouteController("/api/v1/content/collection/{id}/collaboration")
class GetCollectionCollaboration(
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val service: CollectionService,
) : Route<ByteArray>() {

    override fun serializer(): KSerializer<ByteArray>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): ByteArray? {
        val id = UUID.parse(call.pathParameters["id"] ?: throw IllegalArgumentException("id is required"))
        val languageTag = call.request.queryParameters["languageTag"] ?: throw IllegalArgumentException("language tag is required")
        val collection = service.getById(id) ?: return null
        collectionPermissionEvaluator.verifyAllowed(authenticationContext, collection, PermissionAction.EDIT)
        return service.getCollaboration(collection.id, languageTag)?.content
    }
}