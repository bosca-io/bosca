package bosca.git.transport

import bosca.git.service.RepositoryService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Redirects a repository's clone URL to its repository page in Studio when the
 * URL is opened directly instead of being used by a Git smart-HTTP client.
 */
@RouteController(
    path = "/{owner}/{repo}",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.NONE
)
class GitRepositoryRedirectRoute(
    private val repositoryService: RepositoryService,
    private val application: BoscaApplication,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val owner = call.pathParameters["owner"] ?: return call.respond(HttpStatusCode.BadRequest)
        val repo = call.pathParameters["repo"]
            ?.removeSuffix(".git")
            ?: return call.respond(HttpStatusCode.BadRequest)

        val repository = repositoryService.findByOwnerAndSlug(owner, repo)
            ?: return call.respond(HttpStatusCode.NotFound)
        val studioUrl = application.environment.config.propertyOrNull("app.url")
            ?.getString()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return call.respond(HttpStatusCode.ServiceUnavailable)

        call.respondRedirect("$studioUrl/git/repositories/${repository.id}")
    }
}
