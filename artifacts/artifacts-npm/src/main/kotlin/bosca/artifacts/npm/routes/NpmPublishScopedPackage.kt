package bosca.artifacts.npm.routes

import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Publishes a new version of a scoped package. Receives the npm publish document
 * containing version metadata and a base64-encoded tarball in `_attachments`.
 */
@RouteController("/npm/@{scope}/{name}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class NpmPublishScopedPackage(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val scope = call.pathParameters["scope"] ?: return call.respond(HttpStatusCode.NotFound)
        val name = call.pathParameters["name"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!isValidNpmScope(scope) || !isValidNpmName(name)) return call.respond(HttpStatusCode.BadRequest)
        handleNpmPublish(call, "@$scope", name, repoService, blobService, permissionEvaluator, authenticationContext)
    }
}
