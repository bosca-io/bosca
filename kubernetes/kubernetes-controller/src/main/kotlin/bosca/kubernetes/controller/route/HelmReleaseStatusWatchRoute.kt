package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.HelmReleaseDecoder
import bosca.kubernetes.model.K8sHelmRelease
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.Secret
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
 * Live release-status stream for a single helm release.
 *
 * URL: `GET /clusters/{id}/helm/releases/{namespace}/{name}/watch`
 *
 * Helm 3 represents every revision as a Secret labelled `owner=helm`,
 * `name=<release>`. A successful install / upgrade / rollback writes
 * a new Secret with a higher revision; the previous Secret has its
 * `status` label flipped to `superseded`. Watching those Secrets is
 * the canonical way to follow release lifecycle without polling the
 * helm binary.
 *
 * The route narrows the watch with `withLabels(owner=helm,name=<release>)`
 * inside [namespace] and emits the highest-revision decoded release
 * on every observed change. The studio's install / upgrade UI uses
 * this to surface `pending-install → deployed` transitions live
 * during long-running rollouts.
 *
 * Authorization: REQUIRED JWT + independent admin re-check.
 */
@RouteController(
    path = "/clusters/{id}/helm/releases/{namespace}/{name}/watch",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class HelmReleaseStatusWatchRoute(
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
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val client = pool.get(clusterId)
        call.response.respondStreaming(NDJSON) { output ->
            val channel = Channel<K8sHelmRelease>(capacity = 32)

            // Track the current highest revision so the stream stays
            // monotonically increasing — fabric8 will replay the
            // existing Secrets on watch open, and we filter out the
            // older revisions to avoid flapping the UI.
            val highest = java.util.concurrent.atomic.AtomicInteger(-1)

            val watcher = object : Watcher<Secret> {
                override fun eventReceived(action: Watcher.Action, resource: Secret) {
                    when (action) {
                        Watcher.Action.ADDED, Watcher.Action.MODIFIED -> {
                            if (!HelmReleaseDecoder.isHelmReleaseSecret(resource)) return
                            val release = HelmReleaseDecoder.decodeRelease(resource) ?: return
                            // Update high-water-mark; emit only when
                            // the incoming revision is at-or-above it.
                            // Equal-revision MODIFIED events carry
                            // status transitions (pending → deployed),
                            // so we keep those.
                            val current = highest.get()
                            if (release.revision >= current) {
                                highest.set(release.revision)
                                channel.trySend(release)
                            }
                        }
                        Watcher.Action.DELETED, Watcher.Action.ERROR -> { /* ignore — non-terminal */ }
                        else -> { /* BOOKMARK and unknown — ignored */ }
                    }
                }

                override fun onClose(cause: WatcherException?) {
                    if (cause != null) {
                        log.debug("helm release watch closed for cluster {} {}/{}: {}", clusterId, namespace, name, cause.message)
                    }
                    channel.close()
                }
            }

            var watch: Watch? = null
            try {
                withContext(Dispatchers.IO) {
                    val opened = client.secrets()
                        .inNamespace(namespace)
                        .withLabel("owner", "helm")
                        .withLabel("name", name)
                        .watch(watcher)
                    watch = opened
                }
                while (currentCoroutineContext().isActive) {
                    val release = channel.receiveCatching().getOrNull() ?: break
                    val line = json.encodeToString(K8sHelmRelease.serializer(), release) + "\n"
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } finally {
                runCatching { watch?.close() }
                    .onFailure { log.warn("failed closing helm release watch for {}/{} on {}: {}", namespace, name, clusterId, it.message) }
                channel.close()
            }
        }
        return Unit
    }

    companion object {
        private val log = LoggerFactory.getLogger(HelmReleaseStatusWatchRoute::class.java)
        private val NDJSON = ContentType("application", "x-ndjson")
    }
}
