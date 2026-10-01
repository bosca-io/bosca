package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.cluster.ClusterInformerRegistry
import bosca.kubernetes.controller.util.formatAge
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.NamespacesResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Lists namespaces in the cluster identified by `{id}`.
 *
 * ## Authorization
 *
 * - The route is `RouteAuthentication.REQUIRED`: the framework rejects
 *   the request with 401 if the caller does not present a valid JWT.
 * - The route then independently verifies that the authenticated
 *   principal is in the `administrators` group via [GroupEvaluator] —
 *   bosca-server's resolver also checks this, but we re-check here so
 *   a bug or compromise in bosca-server cannot grant cluster access.
 *
 * ## Data path
 *
 * Reads come from the fabric8 cache via [ClusterClientPool]: the first
 * request for a cluster builds a [io.fabric8.kubernetes.client.KubernetesClient]
 * from the stored kubeconfig; subsequent requests reuse the cached
 * client (and its connection pool). The blocking `list()` call runs
 * on [Dispatchers.IO] so it doesn't park the event loop.
 *
 * Counts (workloads / pods / services) come from per-cluster
 * informer caches via [ClusterInformerRegistry]. The first read for a
 * cluster warms the informers and sees zeros for a few moments;
 * subsequent reads pull from in-memory maps with no extra API calls.
 */
@RouteController(
    path = "/clusters/{id}/namespaces",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class NamespacesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val informers: ClusterInformerRegistry,
) : Route<NamespacesResponse>() {

    override fun serializer(): KSerializer<NamespacesResponse> = NamespacesResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): NamespacesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return null
            }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return null
            }

        val client = pool.get(clusterId)
        val items = withContext(Dispatchers.IO) {
            try {
                client.namespaces().list().items
            } catch (e: Exception) {
                log.warn("fabric8 namespaces list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val informerSet = informers.get(clusterId)
        return NamespacesResponse(
            items = items.map { ns ->
                val name = ns.metadata?.name ?: "<unknown>"
                val counts = informerSet.counts(name)
                Namespace(
                    name = name,
                    status = ns.status?.phase ?: "Unknown",
                    workloads = counts.workloads,
                    pods = counts.pods,
                    services = counts.services,
                    age = formatAge(ns.metadata?.creationTimestamp, now),
                )
            },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(NamespacesRoute::class.java)
    }
}
