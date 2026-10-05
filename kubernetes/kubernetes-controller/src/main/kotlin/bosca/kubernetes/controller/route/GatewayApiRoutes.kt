package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toWire
import bosca.kubernetes.model.GatewayClassesResponse
import bosca.kubernetes.model.GatewaysResponse
import bosca.kubernetes.model.HttpRoutesResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.gatewayapi.v1.Gateway
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClass
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Gateway API read routes. Each route is REQUIRED-JWT + admin-checked.
 * The typed-model approach uses `client.resources(<Class>)` rather
 * than the generic API — fabric8 picks up the GVK from the model's
 * `@Version`/`@Group` annotations and exposes the standard typed
 * list/watch surface.
 *
 * When the Gateway API CRDs aren't installed on the target cluster
 * (`kubectl api-resources | grep gateway.networking.k8s.io` returns
 * nothing), fabric8 surfaces that as a `Not Found` 404. We treat that
 * as "no Gateway API resources" rather than an error — the studio
 * lists / drawers render an empty state, same as a cluster that just
 * has no Gateways defined.
 */
internal fun isCrdNotInstalled(e: Throwable): Boolean =
    e is KubernetesClientException && e.code == 404

@RouteController(
    path = "/clusters/{id}/gatewayclasses",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class GatewayClassesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<GatewayClassesResponse>() {

    override fun serializer(): KSerializer<GatewayClassesResponse> = GatewayClassesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): GatewayClassesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                GatewayClassesResponse(items = client.resources(GatewayClass::class.java).list().items.map { it.toWire() })
            } catch (e: Exception) {
                if (isCrdNotInstalled(e)) return@withContext GatewayClassesResponse(items = emptyList())
                log.warn("fabric8 gatewayclasses list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(GatewayClassesRoute::class.java) }
}

@RouteController(
    path = "/clusters/{id}/gateways",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class GatewaysRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<GatewaysResponse>() {

    override fun serializer(): KSerializer<GatewaysResponse> = GatewaysResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): GatewaysResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val gwOps = client.resources(Gateway::class.java)
                val gatewayList = if (namespace != null) gwOps.inNamespace(namespace).list() else gwOps.inAnyNamespace().list()

                // Per-gateway route count via a single HTTPRoute scan:
                // one extra list call, then grouped in-memory. Cheaper
                // than a fan-out list per gateway.
                val routeOps = client.resources(HTTPRoute::class.java)
                val routesByParent = mutableMapOf<String, Int>()
                val routes = if (namespace != null) routeOps.inNamespace(namespace).list() else routeOps.inAnyNamespace().list()
                for (r in routes.items) {
                    for (parent in r.spec?.parentRefs.orEmpty()) {
                        val key = "${parent.namespace ?: r.metadata?.namespace.orEmpty()}/${parent.name.orEmpty()}"
                        routesByParent[key] = (routesByParent[key] ?: 0) + 1
                    }
                }

                GatewaysResponse(items = gatewayList.items.map { gw ->
                    val key = "${gw.metadata?.namespace.orEmpty()}/${gw.metadata?.name.orEmpty()}"
                    gw.toWire(routes = routesByParent[key] ?: 0)
                })
            } catch (e: Exception) {
                if (isCrdNotInstalled(e)) return@withContext GatewaysResponse(items = emptyList())
                log.warn("fabric8 gateways list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(GatewaysRoute::class.java) }
}

@RouteController(
    path = "/clusters/{id}/httproutes",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HttpRoutesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<HttpRoutesResponse>() {

    override fun serializer(): KSerializer<HttpRoutesResponse> = HttpRoutesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): HttpRoutesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.resources(HTTPRoute::class.java)
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                HttpRoutesResponse(items = list.items.map { it.toWire() })
            } catch (e: Exception) {
                if (isCrdNotInstalled(e)) return@withContext HttpRoutesResponse(items = emptyList())
                log.warn("fabric8 httproutes list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(HttpRoutesRoute::class.java) }
}
