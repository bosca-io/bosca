package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.WorkloadAggregates
import bosca.kubernetes.controller.util.podStatesOf
import bosca.kubernetes.controller.util.toWorkload
import bosca.kubernetes.model.Workload
import bosca.kubernetes.model.WorkloadKind
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.Job
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
 * Live status stream for a single workload — emits a fresh [Workload]
 * mapping every time the API server modifies the watched resource.
 *
 * URL: `GET /clusters/{id}/workloads/{kind}/{namespace}/{name}/watch`
 *
 * The studio uses this in the workload detail drawer to drive the
 * ready/desired delta, restart count, and status badge without
 * polling. A separate event is fired on every k8s status update —
 * the studio is responsible for diffing what to re-render.
 *
 * Authorization: REQUIRED JWT + independent admin re-check.
 *
 * Reconnection: same 5-minute cap as every other streaming route;
 * the studio composable reconnects automatically.
 */
@RouteController(
    path = "/clusters/{id}/workloads/{kind}/{namespace}/{name}/watch",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class WorkloadStatusWatchRoute(
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
        val kind = call.pathParameters["kind"]?.let {
            runCatching { WorkloadKind.valueOf(it) }.getOrNull()
        } ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val client = pool.get(clusterId)
        call.response.respondStreaming(NDJSON) { output ->
            val channel = Channel<Workload>(capacity = 64)
            val watcher = workloadWatcher(channel, log, clusterId.toString(), kind, client)

            var watch: Watch? = null
            try {
                withContext(Dispatchers.IO) {
                    val opened = when (kind) {
                        WorkloadKind.DEPLOYMENT ->
                            client.apps().deployments().inNamespace(namespace).withName(name)
                                .watch(@Suppress("UNCHECKED_CAST") (watcher as Watcher<Deployment>))
                        WorkloadKind.STATEFUL_SET ->
                            client.apps().statefulSets().inNamespace(namespace).withName(name)
                                .watch(@Suppress("UNCHECKED_CAST") (watcher as Watcher<StatefulSet>))
                        WorkloadKind.DAEMON_SET ->
                            client.apps().daemonSets().inNamespace(namespace).withName(name)
                                .watch(@Suppress("UNCHECKED_CAST") (watcher as Watcher<DaemonSet>))
                        WorkloadKind.REPLICA_SET ->
                            client.apps().replicaSets().inNamespace(namespace).withName(name)
                                .watch(@Suppress("UNCHECKED_CAST") (watcher as Watcher<ReplicaSet>))
                        WorkloadKind.JOB ->
                            client.batch().v1().jobs().inNamespace(namespace).withName(name)
                                .watch(@Suppress("UNCHECKED_CAST") (watcher as Watcher<Job>))
                        WorkloadKind.CRON_JOB ->
                            client.batch().v1().cronjobs().inNamespace(namespace).withName(name)
                                .watch(@Suppress("UNCHECKED_CAST") (watcher as Watcher<CronJob>))
                    }
                    watch = opened
                }

                while (currentCoroutineContext().isActive) {
                    val workload = channel.receiveCatching().getOrNull() ?: break
                    val line = json.encodeToString(Workload.serializer(), workload) + "\n"
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } finally {
                runCatching { watch?.close() }
                    .onFailure { log.warn("failed closing workload watch for {}/{}/{}: {}", kind, namespace, name, it.message) }
                channel.close()
            }
        }
        return Unit
    }

    /**
     * Builds a single [Watcher] that handles every workload kind. We
     * type-erase the watcher because each fabric8 watch call wants a
     * specific concrete type; the kind switch happens once at watch
     * setup and is then encoded into the watcher's mapper.
     *
     * `DELETED` actions terminate the stream — the studio re-fetches
     * the list and updates its UI accordingly.
     */
    private fun workloadWatcher(
        channel: Channel<Workload>,
        log: org.slf4j.Logger,
        clusterId: String,
        kind: WorkloadKind,
        client: io.fabric8.kubernetes.client.KubernetesClient,
    ): Watcher<out HasMetadata> = object : Watcher<HasMetadata> {
        override fun eventReceived(action: Watcher.Action, resource: HasMetadata) {
            when (action) {
                Watcher.Action.ADDED, Watcher.Action.MODIFIED -> {
                    val mapped = mapWorkload(client, resource, kind) ?: return
                    channel.trySend(mapped)
                }
                Watcher.Action.DELETED -> channel.close()
                else -> { /* ignore ERROR/BOOKMARK — fabric8's watcher manager handles error recovery */ }
            }
        }

        override fun onClose(cause: WatcherException?) {
            if (cause != null) {
                log.debug("workload watch closed for cluster {} kind {}: {}", clusterId, kind, cause.message)
            }
            channel.close()
        }
    }

    /**
     * Type-routed mapping back to the wire shape. The watcher receives
     * `HasMetadata` (the erased common parent), so we narrow by [kind]
     * and dispatch to the kind-specific `toWorkload()` extension.
     *
     * Replica-driven kinds also list the workload's pods by its label
     * selector so the status ladder can tell a PENDING rollout apart
     * from a failing one — the same distinction the on-demand list
     * makes. The list is one namespaced selector-scoped call per watch
     * event, which is rare enough (spec/status updates on a single
     * resource) to run inline on the watch callback. Selector-less
     * workloads and failed pod lists degrade to the coarse ladder
     * rather than listing the whole namespace.
     */
    private fun mapWorkload(
        client: io.fabric8.kubernetes.client.KubernetesClient,
        resource: HasMetadata,
        kind: WorkloadKind,
    ): Workload? = when (kind) {
        WorkloadKind.DEPLOYMENT -> (resource as? Deployment)?.let {
            it.toWorkload(selectorAggregates(client, it.metadata?.namespace, it.spec?.selector?.matchLabels))
        }
        WorkloadKind.STATEFUL_SET -> (resource as? StatefulSet)?.let {
            it.toWorkload(selectorAggregates(client, it.metadata?.namespace, it.spec?.selector?.matchLabels))
        }
        WorkloadKind.DAEMON_SET -> (resource as? DaemonSet)?.let {
            it.toWorkload(selectorAggregates(client, it.metadata?.namespace, it.spec?.selector?.matchLabels))
        }
        WorkloadKind.REPLICA_SET -> (resource as? ReplicaSet)?.let {
            it.toWorkload(selectorAggregates(client, it.metadata?.namespace, it.spec?.selector?.matchLabels))
        }
        WorkloadKind.JOB -> (resource as? Job)?.toWorkload()
        WorkloadKind.CRON_JOB -> (resource as? CronJob)?.toWorkload()
    }

    /**
     * Pod-state aggregates for the pods matching [matchLabels] in
     * [namespace]. Returns empty aggregates (null pod states → coarse
     * status ladder) when the selector is absent/empty or the list
     * fails — never lists unselected.
     */
    private fun selectorAggregates(
        client: io.fabric8.kubernetes.client.KubernetesClient,
        namespace: String?,
        matchLabels: Map<String, String>?,
    ): WorkloadAggregates {
        if (namespace.isNullOrEmpty() || matchLabels.isNullOrEmpty()) return WorkloadAggregates()
        return runCatching {
            val pods = client.pods().inNamespace(namespace).withLabels(matchLabels).list().items.orEmpty()
            WorkloadAggregates(podStates = podStatesOf(pods))
        }.getOrElse {
            log.debug("pod list for watch status failed in {}: {}", namespace, it.message)
            WorkloadAggregates()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkloadStatusWatchRoute::class.java)
        private val NDJSON = ContentType("application", "x-ndjson")
    }
}
