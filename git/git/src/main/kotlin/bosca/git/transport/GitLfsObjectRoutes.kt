package bosca.git.transport

import bosca.git.dfs.GitBlockingDispatcher
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.model.LfsUploadValidationException
import bosca.git.service.LfsObjectService
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Receives a Basic Transfer upload through the LFS service's verified streaming storage path. */
@RouteController(path = "/{owner}/{repo}.git/info/lfs/objects/{oid}", method = RouteMethod.PUT, authentication = RouteAuthentication.OPTIONAL)
class GitLfsUploadRoute(
    private val repositories: RepositoryService,
    private val permissions: RepositoryPermissionEvaluator,
    private val lfs: LfsObjectService,
) : Route<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repository = resolveLfsRepository(call, authenticationContext, repositories, permissions, PermissionAction.EDIT) ?: return
        val oid = call.pathParameters["oid"].orEmpty()
        if (!validLfsOid(oid)) return call.respondLfsError(HttpStatusCode.BadRequest, "Invalid LFS object oid")
        val size = call.request.headers["Content-Length"]?.toLongOrNull()
            ?: return call.respondLfsError(HttpStatusCode.LengthRequired, "Content-Length is required")
        if (size < 0) return call.respondLfsError(HttpStatusCode.BadRequest, "Invalid LFS object size")
        try {
            // Admitted like any transfer, since each upload holds two threads while it streams. The
            // body is written to the upload's pipe on GitBlockingDispatcher: a write blocked on a
            // full pipe waits only for the reader, which runs on StorageDispatcher and so always gets a
            // thread. The writer needs pooled, long-lived threads: a pipe reads as broken once the
            // thread that last wrote to it has exited (see GitBlockingDispatcher).
            gitTransferPermits.withPermit {
                // Bounded like every transfer: a client trickling its body past the idle timeout
                // would otherwise hold its permit indefinitely.
                withTimeout(GIT_TRANSFER_TIME_LIMIT) {
                    lfs.upload(repository.id, oid, size) { output -> call.request.bodyStreamTo(output, GitBlockingDispatcher) }
                }
            }
        } catch (_: LfsUploadValidationException) {
            return call.respondLfsError(HttpStatusCode.UnprocessableEntity, "LFS object content does not match its oid and size")
        }
        call.respond(HttpStatusCode.OK)
    }
}

/** Streams an LFS object after applying the repository's read permission and token scope. */
@RouteController(path = "/{owner}/{repo}.git/info/lfs/objects/{oid}", method = RouteMethod.GET, authentication = RouteAuthentication.OPTIONAL)
class GitLfsDownloadRoute(
    private val repositories: RepositoryService,
    private val permissions: RepositoryPermissionEvaluator,
    private val lfs: LfsObjectService,
) : Route<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repository = resolveLfsRepository(call, authenticationContext, repositories, permissions, PermissionAction.VIEW) ?: return
        val oid = call.pathParameters["oid"].orEmpty()
        if (!validLfsOid(oid)) return call.respondLfsError(HttpStatusCode.BadRequest, "Invalid LFS object oid")
        val obj = lfs.findByOid(repository.id, oid)
            ?: return call.respondLfsError(HttpStatusCode.NotFound, "LFS object not found")
        // Opening and closing the storage stream block, so both stay off the event loop.
        withContext(Dispatchers.IO) {
            lfs.download(repository.id, oid).use { input ->
                call.response.header("Content-Length", obj.size.toString())
                // No time limit: a large object on a slow link may take long; a client that stops
                // reading altogether is ended by the streaming response's stall timeout.
                call.respondStreaming(ContentType.Application.OctetStream, HttpStatusCode.OK, timeLimit = null) { output ->
                    output.copyFrom(input)
                }
            }
        }
    }
}

/** Confirms that the uploaded object was verified and recorded by the upload endpoint. */
@RouteController(path = "/{owner}/{repo}.git/info/lfs/objects/verify", method = RouteMethod.POST, authentication = RouteAuthentication.OPTIONAL)
class GitLfsVerifyRoute(
    private val repositories: RepositoryService,
    private val permissions: RepositoryPermissionEvaluator,
    private val lfs: LfsObjectService,
) : Route<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val repository = resolveLfsRepository(call, authenticationContext, repositories, permissions, PermissionAction.EDIT) ?: return
        val body = call.request.bodyText()
        val request = try {
            lfsJson.decodeFromString(LfsObjectRequest.serializer(), body)
        } catch (_: SerializationException) {
            return call.respondLfsError(HttpStatusCode.BadRequest, "Invalid LFS verification request")
        }
        if (!validLfsOid(request.oid) || request.size < 0) return call.respondLfsError(HttpStatusCode.BadRequest, "Invalid LFS object oid or size")
        val obj = lfs.findByOid(repository.id, request.oid)
            ?: return call.respondLfsError(HttpStatusCode.NotFound, "LFS object not found")
        if (obj.size != request.size) return call.respondLfsError(HttpStatusCode.UnprocessableEntity, "LFS object size does not match")
        call.respond(HttpStatusCode.OK)
    }
}
