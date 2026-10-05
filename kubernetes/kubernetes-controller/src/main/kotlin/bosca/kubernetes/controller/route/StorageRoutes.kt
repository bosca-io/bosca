package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toWire
import bosca.kubernetes.model.PvcsResponse
import bosca.kubernetes.model.StorageClassesResponse
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Cluster-scoped: lists every StorageClass. REQUIRED JWT + admin
 * re-check.
 */
@RouteController(
    path = "/clusters/{id}/storageclasses",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class StorageClassesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<StorageClassesResponse>() {

    override fun serializer(): KSerializer<StorageClassesResponse> = StorageClassesResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): StorageClassesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                StorageClassesResponse(items = client.storage().v1().storageClasses().list().items.map { it.toWire() })
            } catch (e: Exception) {
                log.warn("fabric8 storageclasses list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(StorageClassesRoute::class.java) }
}

/**
 * Namespaced PVC list, optionally filtered by `namespace` query
 * parameter. REQUIRED JWT + admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/pvcs",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class PvcsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<PvcsResponse>() {

    override fun serializer(): KSerializer<PvcsResponse> = PvcsResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): PvcsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]
        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                val ops = client.persistentVolumeClaims()
                val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                PvcsResponse(items = list.items.map { it.toWire() })
            } catch (e: Exception) {
                log.warn("fabric8 pvcs list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(PvcsRoute::class.java) }
}
