package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.SECRET_VALUE_REDACTED
import bosca.kubernetes.model.YamlResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Fetches the full YAML manifest for any single resource by GVK + name.
 *
 * URL: `GET /clusters/{id}/yaml?kind=X&name=Y&namespace=Z&group=&version=v1`
 *
 * Used by every detail page's `Manifest` tab. The implementation uses
 * fabric8's generic resource API so it covers built-in kinds *and*
 * arbitrary CRD instances without per-kind wiring.
 *
 * `Serialization.asYaml(resource)` produces a kubectl-style document.
 * Managed-fields stripping is intentionally not applied here — admins
 * routinely want to see *which controller wrote which field* and
 * stripping that info hides drift signals.
 *
 * Authorization: REQUIRED JWT + independent admin re-check.
 *
 * Secret manifests are special-cased: a Secret's `data` and `stringData`
 * values are replaced with [SECRET_VALUE_REDACTED] before serialization, so
 * Secret cleartext (or its base64 wire form) never leaves the controller.
 * Only Secret *references* in other kinds' manifests survive — those are
 * names, not values.
 */
@RouteController(
    path = "/clusters/{id}/yaml",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class YamlRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<YamlResponse>() {

    override fun serializer(): KSerializer<YamlResponse> = YamlResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): YamlResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null

        val params = call.request.queryParameters
        val kind = params["kind"]?.takeIf { it.isNotBlank() }
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = params["name"]?.takeIf { it.isNotBlank() }
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = params["namespace"]?.takeIf { it.isNotBlank() }
        val group = params["group"].orEmpty()
        val version = params["version"]?.takeIf { it.isNotBlank() } ?: "v1"

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                if (kind.equals("Secret", ignoreCase = true) && group.isBlank()) {
                    if (namespace == null) {
                        return@withContext run {
                            call.respond(HttpStatusCode.BadRequest)
                            null
                        }
                    }
                    val secret = client.secrets().inNamespace(namespace).withName(name).get()
                        ?: return@withContext run {
                            call.respond(HttpStatusCode.NotFound)
                            null
                        }
                    // Redact before serializing — a Secret's values must never
                    // leave the controller, not even in their base64 wire form.
                    secret.data = secret.data?.mapValues { SECRET_VALUE_REDACTED }
                    secret.stringData = secret.stringData?.mapValues { SECRET_VALUE_REDACTED }
                    return@withContext YamlResponse(yaml = Serialization.asYaml(secret))
                }
                val context = ResourceDefinitionContext.Builder()
                    .withGroup(group)
                    .withVersion(version)
                    .withKind(kind)
                    .withNamespaced(namespace != null)
                    .build()
                val resources = client.genericKubernetesResources(context)
                val targeted = if (namespace != null) {
                    resources.inNamespace(namespace).withName(name)
                } else {
                    resources.withName(name)
                }
                val resource = targeted.get()
                    ?: return@withContext run {
                        call.respond(HttpStatusCode.NotFound)
                        null
                    }
                YamlResponse(yaml = Serialization.asYaml(resource))
            } catch (e: Exception) {
                log.warn(
                    "fabric8 yaml fetch failed for {}/{}/{} {}/{}: {}",
                    group, version, kind, namespace.orEmpty(), name, e.message,
                )
                throw e
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(YamlRoute::class.java)
    }
}
