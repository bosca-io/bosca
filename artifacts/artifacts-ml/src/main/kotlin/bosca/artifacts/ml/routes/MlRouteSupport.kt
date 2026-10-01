package bosca.artifacts.ml.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall

/**
 * Checks ML model artifact permissions. Returns true if allowed, false if the
 * response has already been sent (401/403).
 */
internal suspend fun mlRequirePermission(
    call: ServerCall,
    permissionEvaluator: ArtifactPermissionEvaluator,
    authenticationContext: AuthenticationContext,
    namespace: String,
    repository: String = "*",
    version: String? = null,
    action: ArtifactAction,
    isPublic: Boolean = false,
): Boolean {
    if (permissionEvaluator.evaluate(authenticationContext, "ml", namespace, repository, version, action, isPublic)) {
        return true
    }
    if (authenticationContext.principal() == null) {
        call.response.header(HttpHeaders.WWWAuthenticate, """Basic realm="Bosca ML Registry"""")
        call.respond(HttpStatusCode.Unauthorized, "")
    } else {
        call.respond(HttpStatusCode.Forbidden, "")
    }
    return false
}
