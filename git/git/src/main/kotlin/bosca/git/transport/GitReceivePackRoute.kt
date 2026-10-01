package bosca.git.transport

import bosca.git.dfs.BoscaDfsObjDatabase
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitBlockingDispatcher
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.PushRateLimiter
import bosca.git.service.RepositoryService
import bosca.git.service.RepositoryWriteLock
import bosca.git.service.withRepositoryWriteLock
import bosca.lock.DistributedLockFactory
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.PackProtocolException
import org.eclipse.jgit.errors.UnpackException
import org.eclipse.jgit.transport.ReceivePack
import org.slf4j.LoggerFactory
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Handles `POST /{owner}/{repo}.git/git-receive-pack`, the data transfer phase of
 * a `git push`. The client sends a packfile with new objects and ref update commands;
 * JGit validates, stores the pack, and updates refs.
 *
 * All I/O is streamed — JGit reads the incoming packfile and writes its response
 * directly, with neither buffered in memory (see [streamGitExchange]).
 */
@RouteController(
    path = "/{owner}/{repo}.git/git-receive-pack",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED
)
class GitReceivePackRoute(
    private val repositoryService: RepositoryService,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val preReceiveHook: GitPreReceiveHook,
    private val postReceiveHook: GitPostReceiveHook,
    private val rateLimiter: PushRateLimiter,
    private val lockFactory: DistributedLockFactory
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val owner = call.pathParameters["owner"] ?: return call.respond(HttpStatusCode.BadRequest)
        val repo = call.pathParameters["repo"] ?: return call.respond(HttpStatusCode.BadRequest)

        val repository = repositoryService.findByOwnerAndSlug(owner, repo)
            ?: return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Repository not found"),
                GIT_RECEIVE_PACK_RESULT,
                HttpStatusCode.NotFound
            )

        try {
            permissionEvaluator.verifyAllowed(authenticationContext, repository, PermissionAction.EDIT)
        } catch (_: SecurityException) {
            call.response.header("WWW-Authenticate", "Basic realm=\"Bosca Git\"")
            return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Authentication required"),
                GIT_RECEIVE_PACK_RESULT,
                HttpStatusCode.Unauthorized
            )
        }

        val principal = authenticationContext.principal()

        if (principal is ScopedAuthenticatedPrincipal && !principal.hasScope("git:write")) {
            return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Token missing required scope: git:write"),
                GIT_RECEIVE_PACK_RESULT,
                HttpStatusCode.Forbidden
            )
        }

        if (repository.archived) {
            return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Repository is archived"),
                GIT_RECEIVE_PACK_RESULT,
                HttpStatusCode.Forbidden
            )
        }

        if (principal != null && !rateLimiter.tryAcquire(principal.id)) {
            return call.respondBytes(
                GitInfoRefsRoute.gitErrorPacket("Rate limit exceeded"),
                GIT_RECEIVE_PACK_RESULT,
                HttpStatusCode.TooManyRequests
            )
        }

        withContext(GitBlockingDispatcher) {
            val dfsRepo = dfsManager.open(repository.id)
            dfsRepo.use { repo ->
                // Hold the per-repository write lock for the whole receive + compaction
                // so this push cannot overlap GC/repair or a sibling push on the same
                // repository (see RepositoryWriteLock). Clones are unaffected — they run
                // lock-free and stay safe via deferred pack reaping.
                val ran = lockFactory.withRepositoryWriteLock(repository.id, RepositoryWriteLock.PUSH_WAIT_MILLIS) { lockHandle ->
                    // JGit commits received/compacted packs inside blocking calls that
                    // coroutine cancellation cannot interrupt; the fence re-verifies
                    // lock ownership at each pack commit so nothing lands after the
                    // lock is lost.
                    (repo.objectDatabase as? BoscaDfsObjDatabase)?.commitPacksFence = lockHandle::ensureHeld
                    val receivePack = ReceivePack(repo)
                    receivePack.setBiDirectionalPipe(false)
                    receivePack.setPreReceiveHook(preReceiveHook.forPusher(principal?.id))
                    receivePack.setPostReceiveHook(postReceiveHook.withInitiatingPrincipal(principal?.id))

                    val isGzipped = call.request.headers["Content-Encoding"]?.contains("gzip") == true

                    // Snapshot the current pack set so we can identify packs that JGit
                    // writes as part of this receive. We compact those packs after the
                    // client response completes (see compactNewlyReceivedPacks).
                    val packsBefore = repo.objectDatabase.packs
                        .map { p -> p.packDescription.packName }
                        .toSet()

                    try {
                        // Taken only now that the write lock is held (see MAX_CONCURRENT_GIT_TRANSFERS).
                        gitTransferPermits.withPermit {
                            streamGitExchange(call, GIT_RECEIVE_PACK_RESULT) { requestBody, response ->
                                val responseOutput = ResponseTrackingOutputStream(response)
                                try {
                                    // Closed here so a gzip stream releases its native inflater promptly.
                                    (if (isGzipped) GZIPInputStream(requestBody) else requestBody).use { input ->
                                        receivePack.receive(input, responseOutput, null)
                                    }
                                } catch (e: PackProtocolException) {
                                    // Before capability negotiation, JGit has no message/sideband stream
                                    // and its fatal protocol error produces no smart-HTTP response. Supply
                                    // an ERR pkt-line only in that case; otherwise preserve JGit's output.
                                    if (responseOutput.bytesWritten == 0L) {
                                        responseOutput.write(
                                            GitInfoRefsRoute.gitErrorPacket(
                                                e.message ?: "Invalid receive-pack request"
                                            )
                                        )
                                    }
                                    log.info(
                                        "Git receive-pack protocol error for repository {}: {}",
                                        repository.id,
                                        e.message,
                                    )
                                } catch (e: UnpackException) {
                                    // The default JGit unpack handler writes an "unpack error" status
                                    // report before throwing. Preserve that report as the HTTP body.
                                    log.warn(
                                        "Git receive-pack unpack error for repository {}: {}",
                                        repository.id,
                                        e.cause?.message ?: e.message,
                                    )
                                }
                            }
                        }
                    } finally {
                        // Once the exchange ends, run any newly-written packs through
                        // DfsPackCompactor so that storage never holds a thin pack beyond the
                        // lifetime of a single push. JGit's DfsPackParser deliberately leaves the
                        // .pack header object count wrong on thin packs (it appends bases past the
                        // original count but skips the header rewrite, see
                        // DfsPackParser.onEndThinPack). DfsPackCompactor runs the pack through
                        // PackWriter, which always emits a self-contained pack with a correct
                        // header — same mechanism DfsGarbageCollector uses. It runs even when the
                        // exchange failed after JGit stored the pack and updated refs (a disconnect,
                        // a stall, the time limit); it does nothing if no pack arrived. Errors are
                        // logged rather than thrown, and the worst case is that the thin pack
                        // remains until the next scheduled GC.
                        withContext(NonCancellable + GitWorkDispatcher) { compactNewlyReceivedPacks(repo, packsBefore) }
                    }
                }
                if (ran == null) {
                    // GC/repair or another push held the write lock past the wait
                    // budget. Nothing was received; tell the client to retry.
                    call.respondBytes(
                        GitInfoRefsRoute.gitErrorPacket("Write failed, please retry shortly"),
                        GIT_RECEIVE_PACK_RESULT,
                        HttpStatusCode.ServiceUnavailable
                    )
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitReceivePackRoute::class.java)
        private val GIT_RECEIVE_PACK_RESULT = ContentType("application", "x-git-receive-pack-result")
    }
}

private class ResponseTrackingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var bytesWritten: Long = 0
        private set

    override fun write(value: Int) {
        out.write(value)
        bytesWritten++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        bytesWritten += length
    }
}
