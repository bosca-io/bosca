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
 * Downloads a tarball for a specific version of an unscoped package.
 */
@RouteController("/npm/{name}/-/{filename}", authentication = RouteAuthentication.OPTIONAL)
class NpmDownloadUnscopedTarball(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val name = call.pathParameters["name"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!isValidNpmName(name)) return call.respond(HttpStatusCode.BadRequest)
        val filename = call.pathParameters["filename"] ?: return call.respond(HttpStatusCode.NotFound)
        handleNpmDownloadTarball(call, "_unscoped", name, filename, repoService, blobService, permissionEvaluator, authenticationContext)
    }
}
