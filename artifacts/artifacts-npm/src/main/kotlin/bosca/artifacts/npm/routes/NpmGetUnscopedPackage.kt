package bosca.artifacts.npm.routes

import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Returns the package metadata document for an unscoped package.
 */
@RouteController("/npm/{name}", authentication = RouteAuthentication.OPTIONAL)
class NpmGetUnscopedPackage(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val name = call.pathParameters["name"] ?: return call.respond(HttpStatusCode.NotFound)
        if (name.startsWith("-") || !isValidNpmName(name)) return call.respond(HttpStatusCode.BadRequest)
        handleNpmGetPackage(call, "_unscoped", name, repoService, blobService, permissionEvaluator, authenticationContext)
    }
}
