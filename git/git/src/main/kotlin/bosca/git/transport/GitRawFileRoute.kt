package bosca.git.transport

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.security.RepositoryPermissionEvaluator
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
import kotlinx.coroutines.withContext
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Serves raw file content from a repository at a specific ref with the correct MIME type.
 * Used for direct-linking images, downloads, and raw file access.
 *
 * URL pattern: `GET /{owner}/{repo}/raw/{ref}/{path}`
 * where {path} captures the remaining segments after {ref}.
 */
@RouteController(
    path = "/{owner}/{repo}/raw/{ref}/{path}",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.OPTIONAL
)
class GitRawFileRoute(
    private val repositoryService: RepositoryService,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val owner = call.pathParameters["owner"] ?: return call.respond(HttpStatusCode.BadRequest)
        val repoSlug = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.BadRequest)
        val ref = call.pathParameters["ref"] ?: return call.respond(HttpStatusCode.BadRequest)
        val path = call.pathParameters["path"] ?: return call.respond(HttpStatusCode.BadRequest)

        val repository = repositoryService.findByOwnerAndSlug(owner, repoSlug)
            ?: return call.respond(HttpStatusCode.NotFound)

        permissionEvaluator.verifyAllowed(authenticationContext, repository, PermissionAction.VIEW)

        withContext(GitWorkDispatcher) {
            val repo = dfsManager.open(repository.id)
            repo.use {
                val commitId = it.resolve(ref) ?: return@withContext call.respond(HttpStatusCode.NotFound)
                val objectId = RevWalk(it).use { revWalk ->
                    val commit = revWalk.parseCommit(commitId)
                    TreeWalk.forPath(it, path, commit.tree)?.use { treeWalk -> treeWalk.getObjectId(0) }
                } ?: return@withContext call.respond(HttpStatusCode.NotFound)

                val loader = it.objectDatabase.open(objectId)
                call.response.header("Content-Length", loader.size.toString())
                // A read failure propagates so the response is aborted rather than silently truncated.
                loader.openStream().use { input ->
                    // No time limit: a large file on a slow link may take long; a client that stops
                    // reading altogether is ended by the streaming response's stall timeout.
                    call.respondStreaming(guessContentType(path), HttpStatusCode.OK, timeLimit = null) { stream ->
                        // JGit's object stream reads storage through runBlocking callbacks; see GitWorkDispatcher.
                        stream.copyFrom(input, GIT_TRANSFER_COPY_BUFFER_SIZE, GitWorkDispatcher)
                    }
                }
            }
        }
    }

    companion object {
        private fun guessContentType(path: String): ContentType {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> ContentType.Text.Html
                "css" -> ContentType.Text.Css
                "js", "mjs" -> ContentType.Text.JavaScript
                "json" -> ContentType.Application.Json
                "xml" -> ContentType.Application.Xml
                "txt", "md", "kt", "java", "py", "rs", "go" -> ContentType.Text.Plain
                "png" -> ContentType.Image.Png
                "jpg", "jpeg" -> ContentType.Image.Jpeg
                "gif" -> ContentType.Image.Gif
                "svg" -> ContentType("image", "svg+xml")
                "pdf" -> ContentType("application", "pdf")
                else -> ContentType.Application.OctetStream
            }
        }
    }
}
