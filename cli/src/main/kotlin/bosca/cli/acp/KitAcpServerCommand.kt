package bosca.cli.acp

import bosca.cli.BoscaCliCommand
import bosca.cli.api.KitApi
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Runs Kit as an ACP v1 agent over standard input and output. */
class KitAcpServerCommand : BoscaCliCommand(name = "acp-server") {
    override fun help(context: Context) = "Expose Kit as an ACP agent for editors and other ACP clients"

    private val timeoutSeconds by option("--timeout-seconds", help = "Maximum time for one Kit turn (1-900)")
        .int()
        .default(300)

    private val url by option("--url", "-u", envvar = "BOSCA_ENDPOINT", help = "Bosca GraphQL endpoint URL")
    private val token by option("--token", "-t", envvar = "BOSCA_TOKEN", help = "Bosca bearer token")
    private val username by option("--username", envvar = "BOSCA_USERNAME", help = "Bosca authentication username")
    private val password by option("--password", envvar = "BOSCA_PASSWORD", help = "Bosca authentication password")

    override fun run() = runBlocking {
        if (timeoutSeconds !in 1..900) throw CliktError("--timeout-seconds must be between 1 and 900")
        val api = KitApi(networkClient(url, token, username, password))
        startKitAcpAgent(this, api, timeoutSeconds * 1_000L)
    }
}

internal fun startKitAcpAgent(
    scope: CoroutineScope,
    api: KitApi,
    timeoutMillis: Long = KitApi.DEFAULT_TIMEOUT_MILLIS,
) {
    val reader = System.`in`.bufferedReader()
    val writer = System.out.bufferedWriter()
    val transport = StdioTransport(
        scope,
        Dispatchers.IO,
        flow {
            while (true) {
                emit(withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break)
            }
        },
        { message ->
            writer.appendLine(message)
            writer.flush()
        },
        "Kit ACP",
    )
    val protocol = Protocol(scope, transport)
    Agent(protocol, KitAcpAgentSupport(api, timeoutMillis))
    protocol.start()
}
