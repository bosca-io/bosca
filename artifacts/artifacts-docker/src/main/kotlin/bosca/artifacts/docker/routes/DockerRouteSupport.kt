package bosca.artifacts.docker.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/** Header required by the OCI Distribution spec on all `/v2/` responses. */
internal const val DOCKER_DISTRIBUTION_HEADER = "Docker-Distribution-API-Version"
internal const val DOCKER_DISTRIBUTION_VERSION = "registry/2.0"

/**
 * Builds a Docker registry error response in the OCI Distribution error format.
 */
internal fun dockerError(code: String, message: String): JsonObject {
    return buildJsonObject {
        putJsonArray("errors") {
            addJsonObject {
                put("code", code)
                put("message", message)
            }
        }
    }
}

/**
 * Evaluates whether the caller has the required permission for a Docker
 * registry operation. When access is denied, responds with a 401 that
 * includes the `WWW-Authenticate` header required by the OCI Distribution
 * Spec so that Docker clients can discover the authentication method and
 * retry with credentials.
 *
 * @return `true` if the caller has access and the route should continue,
 *         `false` if a 401 response has already been sent and the route
 *         should return immediately.
 */
internal suspend fun dockerRequirePermission(
    call: ServerCall,
    permissionEvaluator: ArtifactPermissionEvaluator,
    authenticationContext: AuthenticationContext,
    type: String,
    namespace: String,
    repository: String,
    versionOrTag: String?,
    action: ArtifactAction,
    isPublicNamespace: Boolean = false,
): Boolean {
    if (permissionEvaluator.evaluate(authenticationContext, type, namespace, repository, versionOrTag, action, isPublicNamespace)) {
        return true
    }
    call.response.header("WWW-Authenticate", """Basic realm="Bosca Registry"""")
    call.response.header(DOCKER_DISTRIBUTION_HEADER, DOCKER_DISTRIBUTION_VERSION)
    call.respond(HttpStatusCode.Unauthorized, dockerError("UNAUTHORIZED", "authentication required"))
    return false
}

/**
 * Known OCI/Docker manifest content types. Used to sanitize stored media types
 * so that attacker-controlled Content-Type headers from manifest pushes cannot
 * be reflected verbatim on manifest GET responses.
 */
private val VALID_MANIFEST_MEDIA_TYPES = setOf(
    "application/vnd.docker.distribution.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v1+prettyjws",
    "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.oci.image.index.v1+json",
)

/** Default manifest content type when the stored type is missing or unrecognized. */
internal const val DEFAULT_MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"

/**
 * Returns the given media type if it is a recognized OCI/Docker manifest type,
 * otherwise returns the default manifest media type.
 */
internal fun sanitizeManifestMediaType(mediaType: String?): String {
    return if (mediaType != null && mediaType in VALID_MANIFEST_MEDIA_TYPES) mediaType else DEFAULT_MANIFEST_MEDIA_TYPE
}
