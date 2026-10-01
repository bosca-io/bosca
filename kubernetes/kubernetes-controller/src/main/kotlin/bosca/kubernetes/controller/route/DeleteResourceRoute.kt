package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.DeleteResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Generic resource delete. Locates the resource by GVK + name (and
 * optionally namespace) via fabric8's `genericKubernetesResources()`
 * API so we can delete any kind — built-in or CRD — without
 * maintaining a switch for every type.
 *
 * URL: `POST /clusters/{id}/delete?namespace=...&kind=...&group=...&version=...&name=...`
 *
 * Query params (so the URL stays a single route, no GVK in the path):
 *   * `kind`      — required, the Kubernetes Kind (e.g. `Deployment`)
 *   * `name`      — required, resource name
 *   * `namespace` — optional, omit for cluster-scoped resources
 *   * `group`     — optional, defaults to `""` for core API
 *   * `version`   — optional, defaults to `v1`
 *
 * Authorization mirrors every other route: REQUIRED JWT + admin
 * re-check. Cluster-scoped deletes are particularly dangerous — the
 * studio gates them behind a typed-confirm modal, but the server
 * never trusts that gate.
 */
@RouteController(
    path = "/clusters/{id}/delete",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class DeleteResourceRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<DeleteResponse>() {

    override fun serializer(): KSerializer<DeleteResponse> = DeleteResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): DeleteResponse? {
        groups.verifyHasAdminGroup(authenticationContext)

        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val kind = params["kind"]?.takeIf { it.isNotBlank() }
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = params["name"]?.takeIf { it.isNotBlank() }
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = params["namespace"]?.takeIf { it.isNotBlank() }
        val group = params["group"].orEmpty()
        val version = params["version"]?.takeIf { it.isNotBlank() } ?: "v1"

        val context = ResourceDefinitionContext.Builder()
            .withGroup(group)
            .withVersion(version)
            .withKind(kind)
            .withNamespaced(namespace != null)
            .build()
        val client = pool.get(clusterId)

        return withContext(Dispatchers.IO) {
            try {
                val resources = client.genericKubernetesResources(context)
                val targeted = if (namespace != null) {
                    resources.inNamespace(namespace).withName(name)
                } else {
                    resources.withName(name)
                }
                val statuses = targeted.delete()
                val deleted = statuses.isNotEmpty()
                DeleteResponse(
                    deleted = deleted,
                    details = if (!deleted) "no resource matched" else null,
                )
            } catch (e: Exception) {
                log.warn(
                    "fabric8 delete failed for {}/{}/{} {}/{} on cluster {}: {}",
                    group, version, kind, namespace.orEmpty(), name, clusterId, e.message,
                )
                DeleteResponse(deleted = false, details = e.message)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DeleteResourceRoute::class.java)
    }
}
