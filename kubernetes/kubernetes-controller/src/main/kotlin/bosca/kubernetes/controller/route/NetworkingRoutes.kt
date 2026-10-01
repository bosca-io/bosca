package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toWire
import bosca.kubernetes.model.IngressesResponse
import bosca.kubernetes.model.NetworkPoliciesResponse
import bosca.kubernetes.model.ServicesResponse
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
 * The three networking list routes share enough structure that
 * grouping them in one file keeps the `route/` directory navigable
 * without sacrificing per-class testability. Each is a small @RouteController
 * with its own URL and serializer; nothing is shared at runtime beyond
 * the [GroupEvaluator] and [ClusterClientPool] injections.
 *
 * Authorization on every route: REQUIRED JWT + admin re-check.
 */

@RouteController(
    path = "/clusters/{id}/services",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ServicesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<ServicesResponse>() {

    override fun serializer(): KSerializer<ServicesResponse> = ServicesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): ServicesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.services()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()

                // EndpointSlice powers the per-service endpoint count.
                // It's a separately-failing call — old k8s (<1.21
                // beta, <1.30 GA) doesn't have v1, RBAC can deny it
                // independently of `services`, and the API can be
                // disabled in some distributions. None of those
                // should null out the services list, so the call is
                // isolated and its failure logged at WARN.
                val readyByService: Map<Pair<String, String>, Int> = runCatching {
                    val sliceOps = client.discovery().v1().endpointSlices()
                    val slices = if (namespace != null) sliceOps.inNamespace(namespace).list() else sliceOps.inAnyNamespace().list()
                    slices.items.orEmpty()
                        .groupBy { Pair(it.metadata?.namespace.orEmpty(), it.metadata?.labels?.get("kubernetes.io/service-name").orEmpty()) }
                        .mapValues { (_, sliceList) ->
                            sliceList.sumOf { slice ->
                                slice.endpoints.orEmpty().count { ep -> ep.conditions?.ready != false && !ep.addresses.isNullOrEmpty() }
                            }
                        }
                }.getOrElse {
                    log.warn("fabric8 endpointslices list failed for cluster {} — services list will report 0 endpoints: {}", clusterId, it.message)
                    emptyMap()
                }

                ServicesResponse(items = list.items.map { svc ->
                    val key = Pair(svc.metadata?.namespace.orEmpty(), svc.metadata?.name.orEmpty())
                    svc.toWire(endpointCount = readyByService[key] ?: 0)
                })
            } catch (e: Exception) {
                log.warn("fabric8 services list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(ServicesRoute::class.java) }
}

@RouteController(
    path = "/clusters/{id}/ingresses",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class IngressesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<IngressesResponse>() {

    override fun serializer(): KSerializer<IngressesResponse> = IngressesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): IngressesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.network().v1().ingresses()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                IngressesResponse(items = list.items.map { it.toWire() })
            } catch (e: Exception) {
                log.warn("fabric8 ingresses list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(IngressesRoute::class.java) }
}

@RouteController(
    path = "/clusters/{id}/networkpolicies",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class NetworkPoliciesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<NetworkPoliciesResponse>() {

    override fun serializer(): KSerializer<NetworkPoliciesResponse> = NetworkPoliciesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): NetworkPoliciesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.network().v1().networkPolicies()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                NetworkPoliciesResponse(items = list.items.map { it.toWire() })
            } catch (e: Exception) {
                log.warn("fabric8 networkpolicies list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(NetworkPoliciesRoute::class.java) }
}

/**
 * Shared path-parameter helper used by the networking + RBAC + storage
 * routes. Lives next to the consumers so a `Ctrl-clicking` reader
 * lands on a tiny inline definition rather than a far-off util file.
 */
internal suspend fun ServerCall.requireClusterId(): UUID? {
    val rawId = pathParameters["id"]
    if (rawId == null) {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    return runCatching { UUID.parse(rawId) }.getOrNull().also {
        if (it == null) respond(HttpStatusCode.BadRequest)
    }
}
