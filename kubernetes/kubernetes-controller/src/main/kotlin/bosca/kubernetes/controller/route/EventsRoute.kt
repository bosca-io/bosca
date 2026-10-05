package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toEventWire
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.EventsResponse
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
 * Lists kubernetes events for the cluster identified by `{id}`.
 *
 * Authorization mirrors [NamespacesRoute]: REQUIRED JWT + independent
 * admin re-check.
 *
 * Optional query parameters mirror the GraphQL `events` query:
 *   * `namespace` — exact-match namespace filter (uses fabric8's
 *                   namespaced ops to avoid pulling the full cluster
 *                   stream when scoping is requested)
 *   * `level`     — `INFO`, `WARN`, `ERROR`. Applied client-side
 *                   because kubernetes events don't expose level as a
 *                   selectable field (only `type=Warning|Normal`).
 *   * `limit`     — cap returned rows, defaulting to 200 to keep the
 *                   wire payload bounded for chatty clusters.
 *
 * Events are sorted newest-first by `lastTimestamp` so the studio's
 * tail renders the most recent activity at the top.
 */
@RouteController(
    path = "/clusters/{id}/events",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class EventsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
) : Route<EventsResponse>() {

    override fun serializer(): KSerializer<EventsResponse> = EventsResponse.serializer()

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): EventsResponse? {
        groups.verifyHasAdminGroup(authenticationContext)
        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val namespaceFilter = params["namespace"]
        val levelFilter = params["level"]?.let {
            runCatching { EventLevel.valueOf(it) }.getOrNull()
        }
        val limit = params["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT

        val client = pool.get(clusterId)
        val raw = withContext(Dispatchers.IO) {
            try {
                val ops = client.v1().events()
                val list = if (namespaceFilter != null) {
                    ops.inNamespace(namespaceFilter).list()
                } else {
                    ops.inAnyNamespace().list()
                }
                list.items
            } catch (e: Exception) {
                log.warn("fabric8 events list failed for cluster {}: {}", clusterId, e.message)
                throw e
            }
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val mapped = raw
            .map { it.toEventWire(now) }
            .let { src -> levelFilter?.let { lvl -> src.filter { it.level == lvl } } ?: src }
            .sortedByDescending { it.timestamp }
            .take(limit)
        return EventsResponse(items = mapped)
    }

    companion object {
        private val log = LoggerFactory.getLogger(EventsRoute::class.java)
        private const val DEFAULT_LIMIT = 200
    }
}
