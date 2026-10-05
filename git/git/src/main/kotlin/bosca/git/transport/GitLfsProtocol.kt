package bosca.git.transport

import bosca.git.model.Repository
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.security.model.PermissionAction
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val lfsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
internal val lfsContentType = ContentType("application", "vnd.git-lfs+json")
private val lfsOidPattern = Regex("[0-9a-f]{64}")
internal fun validLfsOid(oid: String) = lfsOidPattern.matches(oid)

@Serializable
internal data class LfsObjectRequest(val oid: String, val size: Long)

@Serializable
private data class LfsRequestError(val message: String)

internal fun ServerCall.respondLfsError(status: HttpStatusCode, message: String) = respondBytes(
    lfsJson.encodeToString(LfsRequestError.serializer(), LfsRequestError(message)).toByteArray(), lfsContentType, status,
)

internal suspend fun resolveLfsRepository(
    call: ServerCall,
    authentication: AuthenticationContext,
    repositories: RepositoryService,
    permissions: RepositoryPermissionEvaluator,
    action: PermissionAction,
): Repository? {
    val owner = call.pathParameters["owner"]
    val repo = call.pathParameters["repo"]
    if (owner == null || repo == null) {
        call.respondLfsError(HttpStatusCode.BadRequest, "Missing repository path")
        return null
    }
    val repository = repositories.findByOwnerAndSlug(owner, repo)
    if (repository == null) {
        call.respondLfsError(HttpStatusCode.NotFound, "Repository not found")
        return null
    }
    val principal = authentication.principal()
    if (principal is ScopedAuthenticatedPrincipal) {
        val scope = if (action == PermissionAction.EDIT) "git:write" else "git:read"
        if (!principal.hasScope(scope)) {
            call.respondLfsError(HttpStatusCode.Forbidden, "Token missing required scope: $scope")
            return null
        }
    }
    return try {
        permissions.verifyAllowed(authentication, repository, action)
        repository
    } catch (_: SecurityException) {
        if (principal == null) {
            call.response.header("WWW-Authenticate", "Basic realm=\"Bosca Git\"")
            call.respondLfsError(HttpStatusCode.Unauthorized, "Authentication required")
        } else {
            call.respondLfsError(HttpStatusCode.Forbidden, "Permission denied")
        }
        null
    }
}
