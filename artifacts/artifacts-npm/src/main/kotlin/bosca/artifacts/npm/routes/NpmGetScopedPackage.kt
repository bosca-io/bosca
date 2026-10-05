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
 * Returns the package metadata document for a scoped package. npm/pnpm clients
 * call this to resolve package versions and tarball URLs during `npm install`.
 */
@RouteController("/npm/@{scope}/{name}", authentication = RouteAuthentication.OPTIONAL)
class NpmGetScopedPackage(
    private val repoService: ArtifactRepositoryService,
    private val blobService: BlobStorageService,
    private val permissionEvaluator: ArtifactPermissionEvaluator,
) : APIRoute<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val scope = call.pathParameters["scope"] ?: return call.respond(HttpStatusCode.NotFound)
        val name = call.pathParameters["name"] ?: return call.respond(HttpStatusCode.NotFound)
        if (!isValidNpmScope(scope) || !isValidNpmName(name)) return call.respond(HttpStatusCode.BadRequest)
        handleNpmGetPackage(call, "@$scope", name, repoService, blobService, permissionEvaluator, authenticationContext)
    }
}
