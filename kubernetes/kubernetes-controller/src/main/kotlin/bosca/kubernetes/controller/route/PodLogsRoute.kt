package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.EventLevel
import bosca.kubernetes.model.LogLevelInference
import bosca.kubernetes.model.LogLine
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Streams container log lines for a pod as newline-delimited JSON.
 *
 * URL: `GET /clusters/{id}/pods/{namespace}/{name}/logs`
 *
 * Query parameters:
 *   * `container`  — container name within the pod (defaults to the
 *                    first container when the pod is single-container,
 *                    fabric8 picks for multi-container).
 *   * `follow`     — `true` (default) keeps the stream open as new
 *                    lines arrive; `false` returns the tail and ends.
 *   * `tailLines`  — number of historical lines to seed the stream
 *                    with (default: 100).
 *
 * Wire format: each line of the response body is a complete JSON
 * encoding of [LogLine] followed by `\n`. The content type is
 * `application/x-ndjson` so a streaming client can parse one record
 * per line without buffering the full body.
 *
 * ## Authorization
 *
 * REQUIRED JWT plus an independent `administrators` re-check inside
 * `execute()`. Log access is privileged — secrets often leak into
 * logs and a credential thief reaches them via this route.
 *
 * ## Timeouts and reconnection
 *
 * The underlying `respondStreaming` enforces a 5-minute timeout on a
 * single response (a framework-wide cap against runaway streams). The
 * bosca-server subscription completes when the upstream HTTP body
 * closes; the studio's log viewer detects that and re-subscribes,
 * which transparently restarts the stream at the next tick. The
 * reconnection-friendly default is intentional — log streams should
 * survive pod restarts, controller restarts, and network blips
 * regardless.
 */
@RouteController(
    path = "/clusters/{id}/pods/{namespace}/{name}/logs",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class PodLogsRoute(
    private val groups: GroupEvaluator,
    private val pool: ClusterClientPool,
    private val json: Json,
) : Route<Unit>() {

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ): Unit? {
        groups.verifyHasAdminGroup(authenticationContext)

        val rawId = call.pathParameters["id"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val clusterId = runCatching { UUID.parse(rawId) }.getOrNull()
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val namespace = call.pathParameters["namespace"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }
        val podName = call.pathParameters["name"]
            ?: return null.also { call.respond(HttpStatusCode.BadRequest) }

        val params = call.request.queryParameters
        val container = params["container"]
        val follow = params["follow"]?.toBooleanStrictOrNull() ?: true
        val tailLines = (params["tailLines"]?.toIntOrNull() ?: DEFAULT_TAIL).coerceAtLeast(0)

        val client = pool.get(clusterId)

        call.response.respondStreaming(NDJSON, HttpStatusCode.OK) { output ->
            val podOps = client.pods().inNamespace(namespace).withName(podName)
            val containerOps = if (container != null) podOps.inContainer(container) else podOps
            // fabric8's fluent chain expects `usingTimestamps()` before
            // `tailingLines()` because the interface hierarchy puts the
            // timestamp toggle at the root of the chain. Reversing the
            // order results in an Unresolved reference at compile time.
            val tailing = containerOps.usingTimestamps().tailingLines(tailLines)

            if (follow) {
                val watch = tailing.watchLog()
                try {
                    streamLines(
                        input = watch.output,
                        pod = podName,
                        container = container,
                        write = { bytes -> output.write(bytes); output.flush() },
                    )
                } finally {
                    runCatching { watch.close() }
                        .onFailure { log.warn("failed closing log watch for {}/{}: {}", namespace, podName, it.message) }
                }
            } else {
                // Non-follow path: the kubelet returns the tail and closes.
                // Reuse the same line pump by reading the finite stream.
                val stream = withContext(Dispatchers.IO) { tailing.logInputStream }
                stream.use {
                    streamLines(
                        input = it,
                        pod = podName,
                        container = container,
                        write = { bytes -> output.write(bytes); output.flush() },
                    )
                }
            }
        }
        return Unit
    }

    /**
     * Pumps lines out of [input] until the stream closes or the
     * coroutine is cancelled. Reads are dispatched on [Dispatchers.IO]
     * so the event-loop thread isn't blocked, and cancellation is
     * checked between every line so a disconnecting client tears the
     * watch down promptly.
     */
    private suspend fun streamLines(
        input: java.io.InputStream,
        pod: String,
        container: String?,
        write: suspend (ByteArray) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        while (currentCoroutineContext().isActive) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            val record = parseLine(line, pod, container)
            val bytes = (json.encodeToString(LogLine.serializer(), record) + "\n").toByteArray(StandardCharsets.UTF_8)
            write(bytes)
        }
    }

    /**
     * Splits a kubelet-formatted log line into its `RFC3339Nano`
     * timestamp prefix and the body. When timestamps are enabled (we
     * always set `usingTimestamps`), the kubelet writes
     * `2026-05-14T12:34:56.789012345Z message body`. We split on the
     * first space — robust to body content that contains spaces, and
     * tolerant of the (rare) line that arrives without a timestamp by
     * leaving `timestamp` empty.
     */
    private fun parseLine(rawLine: String, pod: String, container: String?): LogLine {
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
            container = container.orEmpty(),
            timestamp = timestamp,
            level = level,
            message = message,
        )
    }

    /**
     * Quick heuristic so we don't accidentally treat a body that
     * starts with `2017-something` (a body, not a kubelet prefix) as
     * a timestamp. kubelet timestamps always have a `T` in position
     * 10, a digit-or-`:` in 13, and end with `Z` or a `±HH:MM` offset.
     * Cheap enough to run per line; the full ISO parse happens in the
     * studio when the user asks for it.
     */
    private fun String.looksLikeTimestamp(): Boolean {
        if (length < 20) return false
        if (this[10] != 'T') return false
        val last = this[length - 1]
        if (last != 'Z' && !(this[length - 6] == '+' || this[length - 6] == '-')) return false
        return this[0].isDigit() && this[1].isDigit() && this[2].isDigit() && this[3].isDigit()
    }

    companion object {
        private val log = LoggerFactory.getLogger(PodLogsRoute::class.java)
        private val NDJSON = ContentType("application", "x-ndjson")
        private const val DEFAULT_TAIL = 100
    }
}
