package bosca.git.ci.transport

import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.PipelineArtifactService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.serialization.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@RouteController(
    path = "/ci/artifacts/{repositoryId}/{runId}/{name}",
    method = RouteMethod.PUT,
    authentication = RouteAuthentication.REQUIRED
)
class ArtifactUploadRoute(
    private val artifactService: PipelineArtifactService,
    private val repositoryService: RepositoryService,
    private val runService: PipelineRunService,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repositoryId = UUID.parse(call.pathParameters["repositoryId"] ?: return call.respond(HttpStatusCode.BadRequest))
        val runId = UUID.parse(call.pathParameters["runId"] ?: return call.respond(HttpStatusCode.BadRequest))
        val name = call.pathParameters["name"] ?: return call.respond(HttpStatusCode.BadRequest)

        val repository = repositoryService.findById(repositoryId)
            ?: return call.respond(HttpStatusCode.NotFound)
        permissionEvaluator.verifyAllowed(authenticationContext, repository, PermissionAction.EDIT)

        val run = runService.findById(runId) ?: return call.respond(HttpStatusCode.NotFound)

        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("ci-artifact-", ".tar.gz")
        }
        // Opening, closing and deleting the temp file block, so the whole exchange stays off the event loop.
        withContext(Dispatchers.IO) {
            try {
                val length = tempFile.outputStream().use { call.request.bodyStreamTo(it) }
                tempFile.inputStream().use { input ->
                    artifactService.upload(repositoryId, runId, run.number, name, input, length)
                }
                call.respond(HttpStatusCode.NoContent)
            } finally {
                tempFile.delete()
            }
        }
    }
}
