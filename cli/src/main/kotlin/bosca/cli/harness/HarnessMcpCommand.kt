package bosca.cli.harness

import bosca.cli.api.NetworkClient
import bosca.cli.mcp.serveMcp
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess
import kotlin.uuid.Uuid

/** Executes a prepared connection without exposing generation credentials in command arguments. */
class HarnessMcpCommand : CliktCommand(name = "mcp") {
    private val key by argument()
    private val control by option("--control").default("/state/control")
    override fun run(): Unit = runBlocking {
        val processes = Json.decodeFromString(MapSerializer(String.serializer(), HarnessMcpProcess.serializer()), Path.of(control, "mcp-processes.json").readText())
        val connection = processes.getValue(key)
        if (key == "bosca") {
            val network = NetworkClient(connection.environment.getValue("BOSCA_ENDPOINT"))
            network.tokenProvider = { connection.environment.getValue("BOSCA_TOKEN") }
            serveMcp(network, Uuid.parse(connection.environment.getValue("BOSCA_MEMORY_SESSION_ID")))
        } else {
            val process = ProcessBuilder(connection.command).inheritIO().apply {
                environment().clear(); environment().putAll(processEnvironment(System.getenv()) + connection.environment)
            }.start()
            Runtime.getRuntime().addShutdownHook(Thread { runBlocking { stopHarnessProcess(process, false) } })
            exitProcess(process.waitFor())
        }
    }
}
