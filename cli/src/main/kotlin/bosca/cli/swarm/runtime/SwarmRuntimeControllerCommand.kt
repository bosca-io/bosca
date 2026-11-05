package bosca.cli.swarm.runtime

import bosca.security.encryption.EncryptionServiceImpl
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.readText

/** Manager-side service; registry credentials authenticate Docker requests and are not mounted in harness containers. */
class SwarmRuntimeControllerCommand : CliktCommand(name = "runtime-controller") {
    private val config by option("--config", help = "Manager-local runtime profiles JSON").required()
    private val host by option("--host").default("0.0.0.0")
    private val port by option("--port").int().default(8083)
    override fun help(context: Context) = "Manage isolated Kit runtime services on a Swarm manager"
    override fun run() {
        val settings = Json.decodeFromString(SwarmRuntimeConfiguration.serializer(), Path.of(config).readText())
        val registryAuth = settings.profiles.values.mapNotNull { it.registryAuthSecret }.distinct().associateWith { name ->
            require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && name !in setOf(".", "..")) { "Invalid registry credential filename" }
            Json.decodeFromString(SwarmRuntimeRegistryAuth.serializer(), Path.of("/run/secrets", name).readText())
        }
        val token = requireNotNull(System.getenv(settings.tokenEnvironment)) { "Missing Swarm controller token environment" }
        require(token.length >= 32 && token.none(Char::isWhitespace)) { "Invalid Swarm controller token" }
        require(settings.encryptionKeyEnvironment.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Invalid encryption environment reference" }
        requireNotNull(System.getenv(settings.encryptionKeyEnvironment)) { "Missing Bosca encryption key environment" }
        val configuration = buildJsonObject { putJsonObject("security") { putJsonObject("encryption") { put("key", "$" + settings.encryptionKeyEnvironment) } } }
        val application = BoscaApplication(ApplicationConfig.load(configuration.toString().byteInputStream()), ownsServices = false)
        val service = SwarmRuntimeService(CurlSwarmEngine(settings.socket), EncryptionServiceImpl(application), settings, application.json, registryAuth)
        swarmRuntimeServer(service, application.json, token, host, port).start(wait = true)
    }
}
