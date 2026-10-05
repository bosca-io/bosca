package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toWire
import bosca.kubernetes.model.HpasResponse
import bosca.kubernetes.model.K8sHpa
import bosca.kubernetes.model.PdbsResponse
import bosca.kubernetes.model.UpdateHpaLimitsRequest
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Namespaced HorizontalPodAutoscaler (autoscaling/v2) list, optionally
 * filtered by `namespace` query parameter. REQUIRED JWT + admin
 * re-check.
 */
@RouteController(
    path = "/clusters/{id}/hpas",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HpasRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<HpasResponse>() {

    override fun serializer(): KSerializer<HpasResponse> = HpasResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HpasResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.autoscaling().v2().horizontalPodAutoscalers()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                HpasResponse(items = list.items.map { it.toWire() })
            } catch (e: Exception) {
                log.warn("fabric8 hpas list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HpasRoute::class.java) }
}

/**
 * Namespaced PodDisruptionBudget (policy/v1) list, optionally filtered
 * by `namespace` query parameter. REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/pdbs",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class PdbsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<PdbsResponse>() {

    override fun serializer(): KSerializer<PdbsResponse> = PdbsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): PdbsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.policy().v1().podDisruptionBudget()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                PdbsResponse(items = list.items.map { it.toWire() })
            } catch (e: Exception) {
                log.warn("fabric8 pdbs list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(PdbsRoute::class.java) }
}

/**
 * Updates an HPA's replica bounds — the quick-edit counterpart of the
 * YAML editor, mirroring [ScaleWorkloadRoute]'s shape.
 *
 * URL: `POST /clusters/{id}/hpas/{namespace}/{name}/limits`
 * Body: `{ "minReplicas": <Int>, "maxReplicas": <Int> }`
 *
 * Bounds are validated here (`1 <= min <= max`) before the edit; the
 * API server still applies its own validation on top. Returns the
 * freshly-updated wire HPA so the studio can render the new bounds
 * without a follow-up list call.
 */
@RouteController(
    path = "/clusters/{id}/hpas/{namespace}/{name}/limits",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.REQUIRED,
)
class UpdateHpaLimitsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<K8sHpa>() {

    override fun serializer(): KSerializer<K8sHpa> = K8sHpa.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): K8sHpa? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val body = call.receive<UpdateHpaLimitsRequest>()
        if (body.minReplicas < 1 || body.maxReplicas < body.minReplicas) {
            call.respond(HttpStatusCode.BadRequest)
            return null
        }

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).withName(name)
                .edit { hpa ->
                    HorizontalPodAutoscalerBuilder(hpa)
                        .editSpec()
                        .withMinReplicas(body.minReplicas)
                        .withMaxReplicas(body.maxReplicas)
                        .endSpec()
                        .build()
                }
                .toWire()
        }
    }
}
