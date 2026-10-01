package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.helm.HelmRuntime
import bosca.kubernetes.model.HelmReleaseTextResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Returns the merged values YAML for a single helm release.
 *
 * URL: `GET /clusters/{id}/helm/releases/{namespace}/{name}/values[?revision=N]`
 *
 * Shells out to `helm get values <release> --all` to surface the
 * effective values — chart defaults merged with caller overrides — at
 * the requested revision (current revision by default).
 *
 * REQUIRED JWT + admin re-check. Values can include sensitive operator
 * input (DB URLs, API tokens), so the same admin-only floor applies.
 */
@RouteController(
    path = "/clusters/{id}/helm/releases/{namespace}/{name}/values",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmReleaseValuesRoute(
    private val groups: GroupEvaluator,
    private val helm: HelmRuntime,
) : Route<HelmReleaseTextResponse>() {

    override fun serializer(): KSerializer<HelmReleaseTextResponse> = HelmReleaseTextResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmReleaseTextResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val revision = call.request.queryParameters["revision"]?.toIntOrNull()
        return try {
            HelmReleaseTextResponse(yaml = helm.getValues(clusterId, namespace, name, revision))
        } catch (e: Exception) {
            log.warn("helm get values failed for {}/{} on {}: {}", namespace, name, clusterId, e.message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmReleaseValuesRoute::class.java) }
}

/**
 * Returns the rendered manifest YAML for a single helm release.
 *
 * URL: `GET /clusters/{id}/helm/releases/{namespace}/{name}/manifest[?revision=N]`
 *
 * Shells out to `helm get manifest <release>` to emit the rendered
 * Deployments / Services / ConfigMaps / etc. — the same multi-document
 * YAML stream `helm install` would apply.
 *
 * REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/helm/releases/{namespace}/{name}/manifest",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmReleaseManifestRoute(
    private val groups: GroupEvaluator,
    private val helm: HelmRuntime,
) : Route<HelmReleaseTextResponse>() {

    override fun serializer(): KSerializer<HelmReleaseTextResponse> = HelmReleaseTextResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HelmReleaseTextResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val revision = call.request.queryParameters["revision"]?.toIntOrNull()
        return try {
            HelmReleaseTextResponse(yaml = helm.getManifest(clusterId, namespace, name, revision))
        } catch (e: Exception) {
            log.warn("helm get manifest failed for {}/{} on {}: {}", namespace, name, clusterId, e.message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HelmReleaseManifestRoute::class.java) }
}
