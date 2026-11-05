package bosca.cli.harness

import bosca.ai.agents.model.McpTransportType
import bosca.ai.kit.harness.KitMcpConnection
import bosca.ai.kit.harness.KitMcpSecretReference
import bosca.ai.kit.harness.KitMcpStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.http.Url
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.*

/** Prepares the existing MCP connection contracts using the CLI's MCP SDK. */
internal class HarnessMcpConnections(
    private val control: Path,
    private val environment: Map<String, String>,
    private val secrets: MutableSet<String>,
    private val cli: String,
    private val egress: HarnessEgress? = null,
) {
    var plans: List<KitMcpConnection>? = null
        private set
    private var statuses = emptyList<KitMcpStatus>()
    private val prepared = mutableMapOf<String, Pair<KitMcpConnection, Map<String, String>>>()

    private fun resolve(references: Map<String, KitMcpSecretReference>) = references.mapValues { (_, reference) ->
        require(reference.environment.matches(Regex("[A-Z][A-Z0-9_]{0,127}")) && !reference.environment.startsWith("BOSCA_")) { "Invalid secret reference" }
        val value = requireNotNull(environment[reference.environment]) { "Missing mounted secret" }
        require(value.isNotEmpty() && value.none { it in "\r\n\u0000" } && reference.prefix in listOf("", "Bearer ", "Basic ")) { "Invalid mounted secret" }
        secrets += value
        reference.prefix + value
    }

    suspend fun configure(connections: List<KitMcpConnection>, cwd: Path, sessionId: String): List<KitMcpStatus> {
        plans?.let { require(it == connections) { "MCP configuration requires a new generation" }; return statuses }
        val names = mutableSetOf<String>()
        val processes = mutableMapOf<String, HarnessMcpProcess>()
        environment["BOSCA_TOKEN"]?.let { token ->
            processes["bosca"] = HarnessMcpProcess(listOf(cli, "mcp-server"), mapOf(
                "BOSCA_TOKEN" to token, "BOSCA_ENDPOINT" to requireNotNull(environment["BOSCA_ENDPOINT"]),
                "BOSCA_MEMORY_SESSION_ID" to sessionId, "BOSCA_NO_UPDATE_CHECK" to "1",
            ))
        }
        val result = connections.map { plan ->
            require(plan.key.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && plan.key != "bosca" && names.add(plan.key)) { "Invalid or repeated MCP key" }
            try {
                // Packaged processes use the runtime's egress forwarder like the harness itself.
                val variables = resolve(plan.environment) + if (plan.transport == McpTransportType.STDIO) egress?.environment().orEmpty() else emptyMap()
                val headers = resolve(plan.headers)
                if (plan.transport == McpTransportType.STDIO) {
                    val executable = Path.of(plan.command.first())
                    require(executable.isAbsolute && executable.isRegularFile() && executable.isExecutable()) { "Packaged executable unavailable" }
                }
                check(withTimeoutOrNull(20_000) { probe(plan, variables, headers, cwd); true } == true) { "MCP probe timed out" }
                if (plan.transport == McpTransportType.STDIO) processes[plan.key] = HarnessMcpProcess(plan.command, variables)
                prepared[plan.key] = plan to headers
                KitMcpStatus(plan.key, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Availability is a per-connection result; upstream diagnostics can contain secrets.
                KitMcpStatus(plan.key, false, "MCP ${plan.key} is unavailable. Review its mounted credentials, packaged executable, and endpoint, then restart the session.")
            }
        }
        withContext(Dispatchers.IO) {
            val file = control.resolve("mcp-processes.json")
            file.writeText(Json.encodeToString(MapSerializer(String.serializer(), HarnessMcpProcess.serializer()), processes))
            HarnessArchive.chmod(file, 384)
        }
        plans = connections.toList()
        statuses = result
        return result
    }

    private suspend fun probe(plan: KitMcpConnection, environment: Map<String, String>, headers: Map<String, String>, cwd: Path) {
        val client = Client(Implementation("bosca-runtime", "1"))
        val proxy = plan.url?.let { egress?.proxyFor(it) }
        val http = HttpClient(CIO) {
            install(SSE); followRedirects = false
            // The forwarder adds egress credentials outside the tunnel, so no proxy header is sent here.
            proxy?.let { engine { this.proxy = ProxyBuilder.http(Url(it)) } }
        }
        var process: Process? = null
        try {
            val transport = when (plan.transport) {
                McpTransportType.STDIO -> {
                    val child = withContext(Dispatchers.IO) {
                        ProcessBuilder(plan.command).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                            environment().clear(); environment().putAll(processEnvironment(this@HarnessMcpConnections.environment) + environment)
                        }.start()
                    }
                    process = child
                    StdioClientTransport(child.inputStream.asSource().buffered(), child.outputStream.asSink().buffered())
                }
                McpTransportType.SSE -> SseClientTransport(http, requireNotNull(plan.url)) { headers.forEach { (name, value) -> this.headers.append(name, value) } }
                McpTransportType.STREAMABLE_HTTP -> StreamableHttpClientTransport(http, requireNotNull(plan.url)) { headers.forEach { (name, value) -> this.headers.append(name, value) } }
            }
            client.connect(transport)
            client.listTools()
        } finally {
            withContext(NonCancellable) {
                try { client.close() } finally { http.close(); process?.let { stopHarnessProcess(it, false) } }
            }
        }
    }

    fun apply(servers: JsonArray): JsonArray = JsonArray(servers.map { element ->
        val server = element.jsonObject
        val key = server.getValue("name").jsonPrimitive.content
        if (key == "bosca" || prepared.getValue(key).first.transport == McpTransportType.STDIO) {
            require(server["command"]?.jsonPrimitive?.content == cli && server["args"] == JsonArray(listOf("harness", "mcp", key).map(::JsonPrimitive)) && server["env"]?.jsonArray.orEmpty().isEmpty()) { "MCP process configuration differs" }
            JsonObject(server + ("args" to JsonArray(listOf("harness", "mcp", key, "--control", control.toString()).map(::JsonPrimitive))))
        } else {
            val (plan, headers) = prepared.getValue(key)
            require(server["type"]?.jsonPrimitive?.content == if (plan.transport == McpTransportType.SSE) "sse" else "http")
            require(server["url"]?.jsonPrimitive?.content == plan.url && server["headers"]?.jsonArray.orEmpty().isEmpty()) { "MCP HTTP configuration differs" }
            JsonObject(server + ("headers" to JsonArray(headers.map { (name, value) -> buildJsonObject { put("name", name); put("value", value) } })))
        }
    })
}

internal fun processEnvironment(environment: Map<String, String>): Map<String, String> = environment.filterKeys { it in listOf("PATH", "HOME", "TMPDIR", "LANG", "SSL_CERT_FILE", "SSL_CERT_DIR") }
