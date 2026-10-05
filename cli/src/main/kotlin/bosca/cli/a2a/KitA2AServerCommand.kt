package bosca.cli.a2a

import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import bosca.cli.BoscaCliCommand
import bosca.cli.api.KitApi
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Runs Kit as an HTTP A2A agent for local and remote agent clients. */
class KitA2AServerCommand : BoscaCliCommand(name = "a2a-server") {
    override fun help(context: Context) = "Expose Kit as an A2A JSON-RPC server for other agents"

    private val host by option("--host", help = "HTTP bind address").default(DEFAULT_HOST)
    private val port by option("--port", help = "HTTP listen port").int().default(DEFAULT_PORT)
    private val path by option("--path", help = "A2A JSON-RPC endpoint path").default(DEFAULT_PATH)
    private val publicUrl by option("--public-url", help = "Full externally reachable A2A endpoint for the agent card")
    private val accessToken by option(
        "--access-token",
        envvar = "BOSCA_A2A_ACCESS_TOKEN",
        help = "Bearer token required from A2A clients (mandatory for non-loopback binds)",
    )
    private val timeoutSeconds by option("--timeout-seconds", help = "Maximum time for one Kit turn (1-900)")
        .int()
        .default(300)

    private val url by option("--url", "-u", envvar = "BOSCA_ENDPOINT", help = "Bosca GraphQL endpoint URL")
    private val token by option("--token", "-t", envvar = "BOSCA_TOKEN", help = "Bosca bearer token")
    private val username by option("--username", envvar = "BOSCA_USERNAME", help = "Bosca authentication username")
    private val password by option("--password", envvar = "BOSCA_PASSWORD", help = "Bosca authentication password")

    override fun run() = runBlocking {
        if (port !in 1..65535) throw CliktError("--port must be between 1 and 65535")
        if (timeoutSeconds !in 1..900) throw CliktError("--timeout-seconds must be between 1 and 900")
        val endpointPath = normalizePath(path)
        val remoteBinding = !isLoopbackHost(host)
        if (remoteBinding && accessToken.isNullOrBlank()) {
            throw CliktError("--access-token is required when --host is not a loopback address")
        }
        if (host in WILDCARD_HOSTS && publicUrl.isNullOrBlank()) {
            throw CliktError("--public-url is required when binding to $host")
        }

        val network = networkClient(url, token, username, password)
        val agentUrl = publicUrl?.trimEnd('/') ?: "http://${urlHost(host)}:$port$endpointPath"
        val agentCard = kitAgentCard(agentUrl, !accessToken.isNullOrBlank())
        val server = createKitA2AHttpServer(
            executor = KitA2AAgentExecutor(KitApi(network), timeoutSeconds * 1_000L),
            agentCard = agentCard,
            host = host,
            port = port,
            path = endpointPath,
            accessToken = accessToken,
        )

        echo("Kit A2A server listening at $agentUrl")
        echo("Agent card: http://${urlHost(host)}:$port$AGENT_CARD_PATH")
        server.start(wait = true)
        Unit
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8091
        const val DEFAULT_PATH = "/a2a/kit"
    }
}

internal const val AGENT_CARD_PATH = "/.well-known/agent-card.json"

internal fun createKitA2AHttpServer(
    executor: KitA2AAgentExecutor,
    agentCard: AgentCard,
    host: String,
    port: Int,
    path: String,
    accessToken: String?,
) = embeddedServer(Netty, host = host, port = port) {
    val transport = HttpJSONRPCServerTransport(A2AServer(agentExecutor = executor, agentCard = agentCard))
    install(SSE)
    if (!accessToken.isNullOrBlank()) {
        install(Authentication) {
            bearer(A2A_AUTH_PROVIDER) {
                realm = "Kit A2A"
                authenticate { credentials ->
                    if (authorized("$BEARER_PREFIX${credentials.token}", accessToken)) {
                        UserIdPrincipal("a2a-client")
                    } else {
                        null
                    }
                }
            }
        }
    }
    routing {
        get(AGENT_CARD_PATH) {
            call.respondText(
                text = AGENT_CARD_JSON.encodeToString(AgentCard.serializer(), agentCard),
                contentType = ContentType.Application.Json,
            )
        }
        if (!accessToken.isNullOrBlank()) {
            authenticate(A2A_AUTH_PROVIDER) {
                transport.transportRoutes(this, path)
            }
        } else {
            transport.transportRoutes(this, path)
        }
    }
}

internal fun normalizePath(path: String): String {
    val normalized = path.trim().trim('/')
    require(normalized.isNotEmpty()) { "A2A endpoint path must not be empty" }
    return "/$normalized"
}

internal fun authorized(header: String?, token: String): Boolean {
    val presented = header?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?: return false
    return MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
}

internal fun isLoopbackHost(host: String): Boolean = host.lowercase() in LOOPBACK_HOSTS

internal fun urlHost(host: String): String = if (':' in host && !host.startsWith('[')) "[$host]" else host

private const val BEARER_PREFIX = "Bearer "
private const val A2A_AUTH_PROVIDER = "kit-a2a-bearer"
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost")
private val WILDCARD_HOSTS = setOf("0.0.0.0", "::")
private val AGENT_CARD_JSON = Json { encodeDefaults = true; explicitNulls = false }
