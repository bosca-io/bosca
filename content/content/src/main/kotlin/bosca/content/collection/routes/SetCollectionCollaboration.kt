package bosca.content.collection.routes

import bosca.content.collection.model.CollectionCollaborationInput
import bosca.content.collection.service.CollectionService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

@RouteController("/api/v1/content/collection/{id}/collaboration", RouteMethod.PUT)
class SetCollectionCollaboration(
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val service: CollectionService,
    private val collectionService: CollectionService,
) : Route<HttpStatusCode>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpStatusCode {
        val id = UUID.parse(call.pathParameters["id"] ?: throw IllegalArgumentException("id is required"))
        val languageTag = call.request.queryParameters["languageTag"] ?: throw IllegalArgumentException("language tag is required")
        val collection = service.getById(id) ?: throw NoSuchElementException("No collection found for id $id")
        val body = call.request.bodyBytes()
        collectionPermissionEvaluator.verifyAllowed(authenticationContext, collection, PermissionAction.EDIT)
        collectionService.setCollaboration(CollectionCollaborationInput(id, languageTag, body))
        return HttpStatusCode.Accepted
    }
}