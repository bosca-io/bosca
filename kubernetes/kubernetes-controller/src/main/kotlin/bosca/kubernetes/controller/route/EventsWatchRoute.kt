package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.toEventWire
import bosca.kubernetes.model.K8sEvent
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ContentType
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.Event
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

/**
 * Live event stream — emits a [K8sEvent] every time the kubelet
 * surfaces a new (or modified) event in the watched cluster.
 *
 * URL: `GET /clusters/{id}/events/watch?namespace=...`
 *
 * Wire format: NDJSON, one [K8sEvent] per line. The studio's events
 * ticker plugs straight into the resulting subscription and renders
 * the rolling tail.
 *
 * ## Authorization
 *
 * REQUIRED JWT + independent admin re-check inside `execute()`.
 * Events can carry secret-ish detail (probe failures with config
 * snippets, image pull errors with private registry paths), so the
 * same admin-only floor applies as every other route.
 *
 * ## Reconnection
 *
 * `respondStreaming` enforces a 5-minute cap on a single response.
 * The studio's subscription completes when the upstream HTTP body
 * closes and the composable reconnects, matching the pattern wired
 * for podLogs.
 */
@RouteController(
    path = "/clusters/{id}/events/watch",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class EventsWatchRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.request.queryParameters["namespace"]

        val client = pool.get(clusterId)
        call.response.respondStreaming(NDJSON) { output ->
            // Capacity is generous enough to absorb a burst of events
            // without back-pressuring the fabric8 watcher thread; if a
            // slow client falls behind we drop to oldest-first (the
            // alternative — blocking the watcher — risks the upstream
            // dropping the entire watch).
            val channel = Channel<K8sEvent>(capacity = 256)
            val watcher = object : Watcher<Event> {
                override fun eventReceived(action: Watcher.Action, resource: Event) {
                    // ADDED is the common case (new event observed);
                    // MODIFIED fires when the kubelet bumps the count
                    // on a recurring event — we surface those too so
                    // the studio's tail reflects current state.
                    if (action == Watcher.Action.ADDED || action == Watcher.Action.MODIFIED) {
                        channel.trySend(resource.toEventWire())
                    }
                }

                override fun onClose(cause: WatcherException?) {
                    if (cause != null) {
                        log.debug("event watch closed for cluster {}: {}", clusterId, cause.message)
                    }
                    channel.close()
                }
            }

            var watch: Watch? = null
            try {
                withContext(Dispatchers.IO) {
                    val ops = client.v1().events()
                    val opened = if (namespace != null) {
                        ops.inNamespace(namespace).watch(watcher)
                    } else {
                        ops.inAnyNamespace().watch(watcher)
                    }
                    watch = opened
                }
                while (currentCoroutineContext().isActive) {
                    val event = channel.receiveCatching().getOrNull() ?: break
                    val line = json.encodeToString(K8sEvent.serializer(), event) + "\n"
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } finally {
                runCatching { watch?.close() }
                    .onFailure { log.warn("failed closing event watch for cluster {}: {}", clusterId, it.message) }
                channel.close()
            }
        }
        return Unit
    }

    companion object {
        private val log = LoggerFactory.getLogger(EventsWatchRoute::class.java)
        private val NDJSON = ContentType("application", "x-ndjson")
    }
}
