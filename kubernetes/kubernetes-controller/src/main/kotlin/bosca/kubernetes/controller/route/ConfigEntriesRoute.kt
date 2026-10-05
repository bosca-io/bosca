package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toEntries
import bosca.kubernetes.model.ConfigKind
import bosca.kubernetes.model.ConfigResourceEntriesResponse
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
 * Reads the entries of a single ConfigMap or Secret.
 *
 * URL: `GET /clusters/{id}/config/{kind}/{namespace}/{name}/entries`
 *
 * For ConfigMaps, values are returned as-is; binary payloads carry a
 * `base64:` prefix so the studio renders them as opaque rather than
 * corrupting bytes. For Secrets, values are NEVER returned — every entry
 * carries a fixed redaction marker instead, so a Secret's cleartext never
 * leaves the controller. Access is still logged with the caller's principal.
 *
 * Authorization mirrors every other route: REQUIRED JWT + admin
 * re-check.
 */
@RouteController(
    path = "/clusters/{id}/config/{kind}/{namespace}/{name}/entries",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ConfigEntriesRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<ConfigResourceEntriesResponse>() {

    override fun serializer(): KSerializer<ConfigResourceEntriesResponse> =
        ConfigResourceEntriesResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): ConfigResourceEntriesResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val kind = call.pathParameters["kind"]?.let {
            runCatching { ConfigKind.valueOf(it) }.getOrNull()
        } ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val principal = authenticationContext.principal()
        log.info(
            "config-entries access principal={} cluster={} kind={} ns={} name={}",
            principal?.id, clusterId, kind, namespace, name,
        )

        val client = pool.get(clusterId)
        return withContext(Dispatchers.IO) {
            try {
                when (kind) {
                    ConfigKind.CONFIG_MAP -> {
                        val cm = client.configMaps().inNamespace(namespace).withName(name).get()
                            ?: return@withContext run {
                                call.respond(HttpStatusCode.NotFound)
                                null
                            }
                        ConfigResourceEntriesResponse(items = cm.toEntries())
                    }
                    ConfigKind.SECRET -> {
                        val sec = client.secrets().inNamespace(namespace).withName(name).get()
                            ?: return@withContext run {
                                call.respond(HttpStatusCode.NotFound)
                                null
                            }
                        ConfigResourceEntriesResponse(items = sec.toEntries())
                    }
                }
            } catch (e: Exception) {
                log.warn("fabric8 config entries fetch failed for {} {}/{} on cluster {}: {}",
                    kind, namespace, name, clusterId, e.message)
                throw e
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfigEntriesRoute::class.java)
    }
}
