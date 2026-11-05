package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.ai.kit.harness.RuntimeStatus
import bosca.ai.kit.harness.RuntimeArchiveReceipt
import bosca.ai.kit.harness.RuntimeTurnReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.ProtocolException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Streaming HTTP control plane; the generation and epoch fence every mutating operation. */
class KitRuntimeClient(private val json: Json) {
    private val client = HttpClient.newHttpClient()

    suspend fun respond(session: KitRuntimeSession, token: String, message: kotlinx.serialization.json.JsonObject) = exchange(session, "/v1/permission") {
        val response = send(session, builder(session, token, "/v1/permission")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(message.toString())).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Runtime permission reply failed: HTTP ${response.statusCode()}" }
    }

    suspend fun configureMcp(session: KitRuntimeSession, token: String, connections: List<bosca.ai.kit.harness.KitMcpConnection>): List<bosca.ai.kit.harness.KitMcpStatus> = exchange(session, "/v1/mcp") {
        val body = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(bosca.ai.kit.harness.KitMcpConnection.serializer()), connections)
        val response = send(session, builder(session, token, "/v1/mcp").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Runtime MCP preparation failed: HTTP ${response.statusCode()}" }
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(bosca.ai.kit.harness.KitMcpStatus.serializer()), response.body())
    }

    suspend fun claim(session: KitRuntimeSession, token: String) =
        decode(session, token, "/v1/claim", "POST", RuntimeStatus.serializer())

    suspend fun status(session: KitRuntimeSession, token: String) =
        decode(session, token, "/v1/runtime", "GET", RuntimeStatus.serializer())

    suspend fun start(session: KitRuntimeSession, token: String) =
        decode(session, token, "/v1/start", "POST", RuntimeStatus.serializer())

    suspend fun archive(session: KitRuntimeSession, token: String) =
        decode(session, token, "/v1/archive", "POST", RuntimeArchiveReceipt.serializer())

    suspend fun cancel(session: KitRuntimeSession, token: String, turnId: String) = exchange(session, "/v1/cancel") {
        val body = kotlinx.serialization.json.buildJsonObject { put("turnId", kotlinx.serialization.json.JsonPrimitive(turnId)) }
        val response = send(session, builder(session, token, "/v1/cancel").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Runtime cancellation failed: HTTP ${response.statusCode()}" }
    }

    suspend fun turn(session: KitRuntimeSession, token: String, id: String): RuntimeTurnReceipt? {
        val response = request(session, token, "/v1/turns/$id", "GET")
        if (response.statusCode() == 404) return null
        check(response.statusCode() == 200) { "Runtime receipt failed: HTTP ${response.statusCode()}" }
        return json.decodeFromString(RuntimeTurnReceipt.serializer(), response.body())
    }

    suspend fun download(session: KitRuntimeSession, token: String, target: Path, limit: Long) = exchange(session, "/v1/archive") {
        val response = send(session, builder(session, token, "/v1/archive").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            check(response.statusCode() == 200) { "Runtime archive download failed: HTTP ${response.statusCode()}" }
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= limit) { "Runtime archive exceeds configured limit" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    suspend fun restore(session: KitRuntimeSession, token: String, archive: Path): RuntimeStatus = exchange(session, "/v1/restore") {
        val request = builder(session, token, "/v1/restore")
            .header("X-Archive-Sha256", requireNotNull(session.archiveSha256))
            .header("X-Bosca-Restore-Mode", if (session.restoreWorkspaceOnly) "workspace" else "full")
            .POST(HttpRequest.BodyPublishers.ofFile(archive)).build()
        val response = send(session, request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Runtime restore failed: HTTP ${response.statusCode()}" }
        json.decodeFromString(RuntimeStatus.serializer(), response.body())
    }

    private suspend fun <T> decode(session: KitRuntimeSession, token: String, path: String, method: String, serializer: KSerializer<T>): T {
        val response = request(session, token, path, method)
        check(response.statusCode() == 200) { "Runtime operation $path failed: HTTP ${response.statusCode()}" }
        return json.decodeFromString(serializer, response.body())
    }

    private suspend fun request(session: KitRuntimeSession, token: String, path: String, method: String) = budget(session, path) {
        // Reads and an identical fenced claim are safe after a lost response. Other operations
        // recover through their durable lifecycle rather than repeating an uncertain side effect.
        val retryable = method == "GET" || path == "/v1/claim"
        for (attempt in 0..2) {
            try {
                val response = interruptible {
                    send(session, builder(session, token, path).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                }
                if (!retryable || attempt == 2 || response.statusCode() !in listOf(502, 503, 504)) return@budget response
            } catch (failure: java.io.IOException) {
                if (!retryable || attempt == 2 || failure is SSLException || failure is ProtocolException) throw failure
            }
            val minimum = 100L shl attempt
            delay(Random.nextLong(minimum, minimum * 2 + 1).milliseconds)
        }
        error("Runtime request attempts exhausted")
    }

    private fun builder(session: KitRuntimeSession, token: String, path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(requireNotNull(session.endpoint).trimEnd('/') + path))
            .timeout(Duration.ofSeconds(timeoutSeconds(session, path))).header("Authorization", "Bearer $token")
            .header("X-Bosca-Generation", session.generation.toString())
            .header("X-Bosca-Epoch", session.epoch.toString())
            .apply { session.runtimeInstanceId?.let { header("X-Bosca-Instance", it) } }

    private fun <T> send(session: KitRuntimeSession, request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val response = client.send(request, handler)
        val actual = response.headers().firstValue("X-Bosca-Instance").orElse(null)
        if (session.runtimeInstanceId != null && actual != session.runtimeInstanceId &&
            (actual != null || response.statusCode() in 200..299)) {
            val failure = KitRuntimeReplacedException(session.generation)
            val body = response.body()
            if (body is java.io.InputStream) body.use { throw failure }
            throw failure
        }
        return response
    }

    private fun timeoutSeconds(session: KitRuntimeSession, path: String): Long {
        val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)
        return when (path) {
            "/v1/mcp" -> config.readinessSeconds
            "/v1/restore" -> config.restoreSeconds
            "/v1/archive" -> config.archiveSeconds
            else -> config.connectionSeconds
        }
    }

    private suspend fun <T : Any> exchange(session: KitRuntimeSession, path: String, action: () -> T): T =
        budget(session, path) { interruptible(action) }

    private suspend fun <T : Any> budget(session: KitRuntimeSession, path: String, action: suspend () -> T): T =
        withTimeoutOrNull(timeoutSeconds(session, path).seconds) { action() }
            ?: throw HttpTimeoutException("Runtime operation $path exceeded its configured deadline")

    private suspend fun <T> interruptible(action: () -> T): T = try { runInterruptible(Dispatchers.IO, action) }
    catch (failure: java.io.IOException) {
        // JDK response streams can wrap interruption in IOException.
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
