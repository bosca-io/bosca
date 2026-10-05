package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toWorkload
import bosca.kubernetes.model.ScaleWorkloadRequest
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
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

/**
 * Scales a workload up or down. Supported kinds: `DEPLOYMENT`,
 * `STATEFUL_SET`, `REPLICA_SET`. DaemonSets, Jobs, and CronJobs are
 * rejected with a 400 — they do not support a `replicas` field in the
 * `kubectl scale` sense.
 *
 * URL: `POST /clusters/{id}/workloads/{kind}/{namespace}/{name}/scale`
 * Body: `{ "replicas": <Int> }`
 *
 * Returns the freshly-scaled [Workload] mapped from fabric8's response
 * — the studio can render the new ready/desired delta without a
 * follow-up list call.
 *
 * Authorization mirrors every other route: REQUIRED JWT plus an
 * independent admin re-check inside `execute()`.
 */
@RouteController(
    path = "/clusters/{id}/workloads/{kind}/{namespace}/{name}/scale",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class ScaleWorkloadRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<Workload>() {

    override fun serializer(): KSerializer<Workload> = Workload.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): Workload? {
        groups.verifyHasAdminGroup(authenticationContext)

        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val kind = call.pathParameters["kind"]?.let {
            runCatching { WorkloadKind.valueOf(it) }.getOrNull()
        } ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val body = call.receive<ScaleWorkloadRequest>()
        if (body.replicas < 0) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            when (kind) {
                WorkloadKind.DEPLOYMENT ->
                    client.apps().deployments().inNamespace(namespace).withName(name)
                        .scale(body.replicas).toWorkload()
                WorkloadKind.STATEFUL_SET ->
                    client.apps().statefulSets().inNamespace(namespace).withName(name)
                        .scale(body.replicas).toWorkload()
                WorkloadKind.REPLICA_SET ->
                    client.apps().replicaSets().inNamespace(namespace).withName(name)
                        .scale(body.replicas).toWorkload()
                else -> {
                    call.respond(HttpStatusCode.BadRequest)
                    null
                }
            }
        }
    }
}
