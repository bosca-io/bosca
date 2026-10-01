package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toConfigResource
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResource
import bosca.kubernetes.model.ConfigResourcesResponse
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
 * Lists ConfigMaps and Secrets in `{id}`. Optional filters:
 *   * `namespace` — exact-match namespace filter
 *   * `kind`      — `CONFIG_MAP` / `SECRET` — narrows to one family
 *
 * Values are never included in this response — only the key set. The
 * separate `configEntries` route reads values and is audited.
 *
 * Authorization mirrors every other route: REQUIRED JWT + independent
 * admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/config",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ConfigResourcesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<ConfigResourcesResponse>() {

    override fun serializer(): KSerializer<ConfigResourcesResponse> = ConfigResourcesResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): ConfigResourcesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val namespace = params["namespace"]
        val kind = params["kind"]?.let {
            runCatching { ConfigKind.valueOf(it) }.getOrNull()
        }

        val client = pool.get(clusterId)
        val items = withContext(Dispatchers.IO) {
            try {
                val collected = mutableListOf<ConfigResource>()
                if (kind == null || kind == ConfigKind.CONFIG_MAP) {
                    val ops = client.configMaps()
                    val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                    list.items.mapTo(collected) { it.toConfigResource() }
                }
                if (kind == null || kind == ConfigKind.SECRET) {
                    val ops = client.secrets()
                    val list = if (namespace != null) ops.inNamespace(namespace).list() else ops.inAnyNamespace().list()
                    list.items.mapTo(collected) { it.toConfigResource() }
                }
                collected
            } catch (e: Exception) {
                log.warn("fabric8 config list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }
        return ConfigResourcesResponse(items = items)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfigResourcesRoute::class.java)
    }
}
