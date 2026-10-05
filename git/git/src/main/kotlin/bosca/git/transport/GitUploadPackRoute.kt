package bosca.git.transport

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitBlockingDispatcher
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eclipse.jgit.transport.UploadPackInternalServerErrorException
import org.slf4j.LoggerFactory

import java.util.zip.GZIPInputStream

/**
 * Handles `POST /{owner}/{repo}.git/git-upload-pack`, the data transfer phase of
 * a `git fetch` or `git clone`. The client sends its wants/haves, and JGit responds
 * with a packfile containing the requested objects.
 *
 * All I/O is streamed — neither the request body nor the response packfile is buffered
 * in memory (see [streamGitExchange]); at most [MAX_CONCURRENT_GIT_TRANSFERS] transfers
 * run at once.
 */
@RouteController(
    path = "/{owner}/{repo}.git/git-upload-pack",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.OPTIONAL
)
class GitUploadPackRoute(
    private val repositoryService: RepositoryService,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val owner = call.pathParameters["owner"] ?: return call.respond(HttpStatusCode.BadRequest)
        val repo = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.BadRequest)

        val repository = repositoryService.findByOwnerAndSlug(owner, repo)
            ?: return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Repository not found"),
                GIT_UPLOAD_PACK_RESULT,
                HttpStatusCode.NotFound
            )

        val principal = authenticationContext.principal()
        if (principal is ScopedAuthenticatedPrincipal && !principal.hasScope("git:read")) {
            return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Token missing required scope: git:read"),
                GIT_UPLOAD_PACK_RESULT,
                HttpStatusCode.Forbidden
            )
        }

        try {
            permissionEvaluator.verifyAllowed(authenticationContext, repository, PermissionAction.VIEW)
        } catch (_: SecurityException) {
            val isAnonymous = principal == null
            if (isAnonymous) {
                call.response.header("WWW-Authenticate", "Basic realm=\"Bosca Git\"")
                return call.respondBytes(
                    GitInfoRefsRoute.gitErrorPacket("Authentication required"),
                    GIT_UPLOAD_PACK_RESULT,
                    HttpStatusCode.Unauthorized
                )
            } else {
                return call.respondBytes(
                    GitInfoRefsRoute.gitErrorPacket("Permission denied"),
                    GIT_UPLOAD_PACK_RESULT,
                    HttpStatusCode.Forbidden
                )
            }
        }

        val isGzipped = call.request.headers["Content-Encoding"]?.contains("gzip") == true
        gitTransferPermits.withPermit {
            withContext(GitBlockingDispatcher) {
                dfsManager.open(repository.id).use { dfsRepo ->
                    val uploadPack = UploadPackFactory.create(dfsRepo)
                    streamGitExchange(call, GIT_UPLOAD_PACK_RESULT) { requestBody, response ->
                        try {
                            // Closed here so a gzip stream releases its native inflater promptly.
                            (if (isGzipped) GZIPInputStream(requestBody) else requestBody).use { input ->
                                uploadPack.upload(input, response, null)
                            }
                        } catch (e: UploadPackInternalServerErrorException) {
                            // UploadPack.upload writes a protocol ERR packet before throwing this
                            // sentinel. Do not propagate it: failing the exchange closes the
                            // chunked HTTP response before Git can read the error packet.
                            log.warn(
                                "Git upload-pack failed after reporting an error to the client for repository {}",
                                repository.id,
                                e.cause ?: e,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitUploadPackRoute::class.java)
        private val GIT_UPLOAD_PACK_RESULT = ContentType("application", "x-git-upload-pack-result")
    }
}
