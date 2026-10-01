package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.metrics.MetricsService
import bosca.kubernetes.controller.util.toK8sNode
import bosca.kubernetes.model.NodesResponse
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

/**
 * Lists nodes in the cluster identified by `{id}`.
 *
 * Authorization mirrors [NamespacesRoute]: REQUIRED JWT + independent
 * admin re-check. Node listings carry IPs, instance types, taints,
 * and labels — privileged reconnaissance data — so administrators-only
 * is the floor.
 */
@RouteController(
    path = "/clusters/{id}/nodes",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class NodesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<NodesResponse>() {

    override fun serializer(): KSerializer<NodesResponse> = NodesResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): NodesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val client = pool.get(clusterId)
        val items = withContext(Dispatchers.IO) {
            try {
                val nodes = client.nodes().list().items
                // Pod count per node comes from a single cluster-wide
                // pod list — cheap relative to N parallel filtered
                // lists, and the bare result is needed for several
                // downstream views anyway. We bucket by `spec.nodeName`
                // (the field the kubelet populates once a pod is
                // scheduled); unscheduled / Pending pods land in an
                // empty bucket that no node ever reads.
                val podCounts = client.pods().inAnyNamespace().list().items
                    .groupingBy { it.spec?.nodeName.orEmpty() }
                    .eachCount()
                val nodeMetrics = MetricsService(client).nodeMetrics()
                nodes.map { node ->
                    val name = node.metadata?.name.orEmpty()
                    node.toK8sNode(
                        usage = nodeMetrics[name],
                        podCount = podCounts[name] ?: 0,
                    )
                }
            } catch (e: Exception) {
                log.warn("fabric8 nodes list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
        return NodesResponse(items = items)
    }

    companion object {
        private val log = LoggerFactory.getLogger(NodesRoute::class.java)
    }
}
