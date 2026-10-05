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
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.serialization.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@RouteController(
    path = "/ci/artifacts/{repositoryId}/{runId}/{name}",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED
)
class ArtifactDownloadRoute(
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
        permissionEvaluator.verifyAllowed(authenticationContext, repository, PermissionAction.VIEW)

        val run = runService.findById(runId) ?: return call.respond(HttpStatusCode.NotFound)

        // Closing the storage stream can block, so the exchange stays off the event loop. Opening is
        // cancellation-safe: storage closes a stream it cannot hand back, and nothing suspends between
        // the hand-back and use{}.
        withContext(Dispatchers.IO) {
            val stream = artifactService.download(repositoryId, run.number, name)
                ?: return@withContext call.respond(HttpStatusCode.NotFound)
            stream.use { input ->
                // No time limit: a large artifact on a slow link may take long; a client that stops
                // reading altogether is ended by the streaming response's stall timeout.
                call.respondStreaming(ContentType.Application.OctetStream, HttpStatusCode.OK, timeLimit = null) { response ->
                    response.copyFrom(input)
                }
            }
        }
    }
}
