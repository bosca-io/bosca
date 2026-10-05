package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.LogLevelInference
import bosca.kubernetes.model.LogLine
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
import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Streams a *merged* view of container logs across every pod that
 * belongs to a workload. The studio's workload-detail Logs tab uses
 * this to give an operator one tail across all replicas without
 * opening N socket-per-pod streams.
 *
 * URL: `GET /clusters/{id}/workloads/{namespace}/{kind}/{name}/logs`
 *
 * Pod selection:
 *
 *  * Deployment / StatefulSet / DaemonSet / ReplicaSet / Job — uses
 *    the workload's own `spec.selector` (label selector). That's the
 *    canonical way to find pods and works equally well for fresh
 *    rollouts where pods are still being created.
 *  * CronJob — walks its child Jobs (ownerReference kind=CronJob,
 *    name match) and then their pods. Skipped for v1: CronJobs with
 *    no active child Jobs yield no logs.
 *
 * Wire format mirrors [PodLogsRoute]: one NDJSON [LogLine] per
 * physical log line, with `pod` populated so the studio can colour
 * by source.
 *
 * Cancellation: when the client disconnects, the framework cancels
 * the route coroutine, which cancels the `coroutineScope` below,
 * which interrupts every per-pod IO reader and closes the fabric8
 * `LogWatch` instances in their `finally` blocks.
 */
@RouteController(
    path = "/clusters/{id}/workloads/{namespace}/{kind}/{name}/logs",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class WorkloadLogsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)
        val clusterId = call.requireClusterId() ?: return null
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val kindRaw = call.pathParameters["kind"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val name = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val kind = runCatching { WorkloadKind.valueOf(kindRaw) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val follow = params["follow"]?.toBooleanStrictOrNull() ?: true
        val tailLines = (params["tailLines"]?.toIntOrNull() ?: DEFAULT_TAIL).coerceAtLeast(0)

        val client = pool.get(clusterId)
        val selector = withContext(Dispatchers.IO) {
            try {
                resolveSelector(client, namespace, kind, name)
            } catch (e: Exception) {
                log.warn("workload-logs: failed resolving selector for {}/{}/{}: {}", namespace, kindRaw, name, e.message)
                null
            }
        }
        if (selector == null) {
            call.respond(HttpStatusCode.NotFound)
            return null
        }

        call.response.respondStreaming(NDJSON, HttpStatusCode.OK) { output ->
            // Single shared flow funnels every per-pod reader into the
            // response body. Buffered so a slow client (or stalled
            // pod) can't backpressure the others into starving each
            // other out.
            val sink = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
            coroutineScope {
                val writer = launch {
                    sink.collect { bytes ->
                        output.write(bytes)
                        output.flush()
                    }
                }
                val pods = withContext(Dispatchers.IO) {
                    runCatching {
                        client.pods().inNamespace(namespace).withLabelSelector(selector).list().items
                    }.getOrElse {
                        log.warn("workload-logs: pod list failed for {}/{}/{}: {}", namespace, kindRaw, name, it.message)
                        emptyList()
                    }
                }
                if (pods.isEmpty()) {
                    // Nothing to tail — still hold the connection so
                    // the studio sees the same lifecycle as a normal
                    // empty-but-active stream until the client closes.
                    writer.cancel()
                    return@coroutineScope
                }
                for (pod in pods) {
                    val podName = pod.metadata?.name ?: continue
                    launch(Dispatchers.IO) {
                        streamPod(client, namespace, podName, follow, tailLines, sink)
                    }
                }
            }
        }
        return Unit
    }

    /**
     * Reads the named pod's log stream and pushes NDJSON-encoded
     * [LogLine] records into [sink]. The fabric8 `LogWatch` is closed
     * in a `finally` so an interrupted coroutine doesn't leak the
     * apiserver connection.
     */
    private suspend fun streamPod(
        client: KubernetesClient,
        namespace: String,
        podName: String,
        follow: Boolean,
        tailLines: Int,
        sink: MutableSharedFlow<ByteArray>,
    ) {
        val podOps = client.pods().inNamespace(namespace).withName(podName)
        val tailing = podOps.usingTimestamps().tailingLines(tailLines)
        if (follow) {
            val watch = runCatching { tailing.watchLog() }.getOrElse { e ->
                log.warn("workload-logs: watchLog failed for pod {}: {}", podName, e.message)
                return
            }
            try {
                pumpStream(watch.output, podName, sink)
            } finally {
                runCatching { watch.close() }
                    .onFailure { log.warn("workload-logs: failed closing watch for {}: {}", podName, it.message) }
            }
        } else {
            val stream = runCatching { tailing.logInputStream }.getOrElse { e ->
                log.warn("workload-logs: logInputStream failed for pod {}: {}", podName, e.message)
                return
            }
            stream.use { pumpStream(it, podName, sink) }
        }
    }

    private suspend fun pumpStream(
        input: java.io.InputStream,
        pod: String,
        sink: MutableSharedFlow<ByteArray>,
    ) {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        while (currentCoroutineContext().isActive) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            val record = parseLine(line, pod)
            val bytes = (json.encodeToString(LogLine.serializer(), record) + "\n")
                .toByteArray(StandardCharsets.UTF_8)
            sink.emit(bytes)
        }
    }

    /**
     * Looks up the workload by `(namespace, kind, name)` and returns
     * the label selector used to identify its pods. Each workload
     * kind exposes its selector under `spec.selector`. Returns null
     * if the workload doesn't exist or has no usable selector.
     *
     * CronJob is intentionally not supported here: its pods are owned
     * by intermediate Jobs and the CronJob spec carries no direct
     * pod selector. Operators wanting CronJob logs can drill into a
     * specific Job's pods via the standard pods view.
     */
    private fun resolveSelector(
        client: KubernetesClient,
        namespace: String,
        kind: WorkloadKind,
        name: String,
    ): LabelSelector? = when (kind) {
        WorkloadKind.DEPLOYMENT ->
            client.apps().deployments().inNamespace(namespace).withName(name).get()?.spec?.selector
        WorkloadKind.STATEFUL_SET ->
            client.apps().statefulSets().inNamespace(namespace).withName(name).get()?.spec?.selector
        WorkloadKind.DAEMON_SET ->
            client.apps().daemonSets().inNamespace(namespace).withName(name).get()?.spec?.selector
        WorkloadKind.REPLICA_SET ->
            client.apps().replicaSets().inNamespace(namespace).withName(name).get()?.spec?.selector
        WorkloadKind.JOB ->
            client.batch().v1().jobs().inNamespace(namespace).withName(name).get()?.spec?.selector
        WorkloadKind.CRON_JOB -> null
    }

    private fun parseLine(rawLine: String, pod: String): LogLine {
        val splitIdx = rawLine.indexOf(' ')
        val timestamp: String
        val message: String
        if (splitIdx > 0 && rawLine.substring(0, splitIdx).looksLikeTimestamp()) {
            timestamp = rawLine.substring(0, splitIdx)
            message = rawLine.substring(splitIdx + 1)
        } else {
            timestamp = ""
            message = rawLine
        }
        val level: EventLevel = LogLevelInference.classify(message)
        return LogLine(
            pod = pod,
            container = "",
            timestamp = timestamp,
            level = level,
            message = message,
        )
    }

    private fun String.looksLikeTimestamp(): Boolean {
        if (length < 20) return false
        if (this[10] != 'T') return false
        val last = this[length - 1]
        if (last != 'Z' && !(this[length - 6] == '+' || this[length - 6] == '-')) return false
        return this[0].isDigit() && this[1].isDigit() && this[2].isDigit() && this[3].isDigit()
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkloadLogsRoute::class.java)
        private val NDJSON = ContentType("application", "x-ndjson")
        private const val DEFAULT_TAIL = 100
    }
}
