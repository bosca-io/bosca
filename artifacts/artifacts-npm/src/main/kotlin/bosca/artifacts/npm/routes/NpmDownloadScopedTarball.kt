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
 * Downloads a tarball for a specific version of a scoped package. The tarball
 * is located by filename across all versions of the package.
 */
@RouteController("/npm/@{scope}/{name}/-/{filename}", authentication = RouteAuthentication.OPTIONAL)
class NpmDownloadScopedTarball(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val scope = call.pathParameters["scope"] ?: return call.respond(HttpStatusCode.NotFound)
        val name = call.pathParameters["name"] ?: return call.respond(HttpStatusCode.NotFound)
        val filename = call.pathParameters["filename"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!isValidNpmScope(scope) || !isValidNpmName(name)) return call.respond(HttpStatusCode.BadRequest)
        handleNpmDownloadTarball(call, "@$scope", name, filename, repoService, blobService, permissionEvaluator, authenticationContext)
    }
}
