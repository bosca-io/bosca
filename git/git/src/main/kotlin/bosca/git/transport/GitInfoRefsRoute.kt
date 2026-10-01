package bosca.git.transport

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitBlockingDispatcher
import bosca.git.model.Repository
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
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.RefAdvertiser
import java.io.OutputStream

/**
 * Handles `GET /{owner}/{repo}.git/info/refs?service=git-upload-pack|git-receive-pack`,
 * the discovery step of the git smart HTTP protocol. The client calls this to learn
 * what refs the server has before starting a fetch or push.
 *
 * The ref advertisement is streamed directly to the client — for repositories with
 * thousands of refs this avoids buffering the entire advertisement in memory.
 */
@RouteController(
    path = "/{owner}/{repo}.git/info/refs",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.OPTIONAL
)
class GitInfoRefsRoute(
    private val repositoryService: RepositoryService,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val owner = call.pathParameters["owner"] ?: return call.respond(HttpStatusCode.BadRequest)
        val repo = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.BadRequest)
        val service = call.request.queryParameters["service"]
            ?: return call.respond(HttpStatusCode.BadRequest)

        val repository = repositoryService.findByOwnerAndSlug(owner, repo)
            ?: return call.respondBytes(
                gitErrorPacket("Repository not found"),
                ContentType("application", "x-git-upload-pack-advertisement"),
                HttpStatusCode.NotFound
            )

        when (service) {
            "git-upload-pack" -> {
                if (!verifyAccess(call, authenticationContext, repository, PermissionAction.VIEW)) return
                withContext(GitBlockingDispatcher) {
                    val dfsRepo = dfsManager.open(repository.id)
                    dfsRepo.use {
                        val uploadPack = UploadPackFactory.create(it)
                        call.response.header("Cache-Control", "no-cache")
                        streamAdvertisement(call, GIT_UPLOAD_PACK_ADV, "git-upload-pack") { output ->
                            uploadPack.sendAdvertisedRefs(RefAdvertiser.PacketLineOutRefAdvertiser(PacketLineOut(output)))
                        }
                    }
                }
            }

            "git-receive-pack" -> {
                if (!verifyAccess(call, authenticationContext, repository, PermissionAction.EDIT)) return
                withContext(GitBlockingDispatcher) {
                    val dfsRepo = dfsManager.open(repository.id)
                    dfsRepo.use {
                        val receivePack = ReceivePack(it)
                        call.response.header("Cache-Control", "no-cache")
                        streamAdvertisement(call, GIT_RECEIVE_PACK_ADV, "git-receive-pack") { output ->
                            receivePack.sendAdvertisedRefs(RefAdvertiser.PacketLineOutRefAdvertiser(PacketLineOut(output)))
                        }
                    }
                }
            }

            else -> call.respond(HttpStatusCode.BadRequest)
        }
    }

    private suspend fun streamAdvertisement(
        call: ServerCall,
        contentType: ContentType,
        serviceName: String,
        writeRefs: (OutputStream) -> Unit
    ) {
        gitTransferPermits.withPermit {
            call.respondStreaming(contentType) { stream ->
                // Blocking JGit writes, straight to the response (see streamGitExchange).
                withContext(GitBlockingDispatcher) {
                    val output = stream.outputStream()
                    output.write(pktLine("# service=$serviceName\n"))
                    output.write(pktFlush())
                    writeRefs(output)
                }
            }
        }
    }

    private suspend fun verifyAccess(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
        repository: Repository,
        action: PermissionAction
    ): Boolean {
        val principal = authenticationContext.principal()
        if (principal is ScopedAuthenticatedPrincipal) {
            val requiredScope = if (action == PermissionAction.EDIT) "git:write" else "git:read"
            if (!principal.hasScope(requiredScope)) {
                val errorContentType = ContentType("application", "x-git-upload-pack-advertisement")
                call.respondBytes(gitErrorPacket("Token missing required scope: $requiredScope"), errorContentType, HttpStatusCode.Forbidden)
                return false
            }
        }
        return try {
            permissionEvaluator.verifyAllowed(authenticationContext, repository, action)
            true
        } catch (_: SecurityException) {
            val isAnonymous = principal == null
            val errorContentType = ContentType("application", "x-git-upload-pack-advertisement")
            if (isAnonymous) {
                call.response.header("WWW-Authenticate", "Basic realm=\"Bosca Git\"")
                call.respondBytes(gitErrorPacket("Authentication required"), errorContentType, HttpStatusCode.Unauthorized)
            } else {
                call.respondBytes(gitErrorPacket("Permission denied"), errorContentType, HttpStatusCode.Forbidden)
            }
            false
        }
    }

    companion object {
        private val GIT_UPLOAD_PACK_ADV = ContentType("application", "x-git-upload-pack-advertisement")
        private val GIT_RECEIVE_PACK_ADV = ContentType("application", "x-git-receive-pack-advertisement")

        internal fun pktLine(data: String): ByteArray {
            val len = data.length + 4
            return String.format("%04x%s", len, data).toByteArray()
        }

        internal fun pktFlush(): ByteArray = "0000".toByteArray()

        internal fun gitErrorPacket(message: String): ByteArray {
            return pktLine("ERR $message\n")
        }
    }
}
